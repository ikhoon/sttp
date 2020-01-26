package sttp.client.armeria

import java.io.{ByteArrayInputStream, File, InputStream}
import java.nio.ByteBuffer
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}

import com.github.ghik.silencer.silent
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common._
import com.linecorp.armeria.common.stream.{AbortedStreamException, CancelledSubscriptionException}
import io.netty.buffer.Unpooled
import org.reactivestreams.{Publisher, Subscriber, Subscription}
import sttp.client
import sttp.client.internal.{CrLf, FileHelpers, Iso88591}
import sttp.client.monad.MonadAsyncError
import sttp.client.monad.syntax._
import sttp.client.{
  BasicRequestBody,
  ByteArrayBody,
  ByteBufferBody,
  FileBody,
  IgnoreResponse,
  InputStreamBody,
  MappedResponseAs,
  MultipartBody,
  NoBody,
  NothingT,
  Request,
  Response,
  ResponseAs,
  ResponseAsByteArray,
  ResponseAsFile,
  ResponseAsFromMetadata,
  ResponseAsStream,
  ResponseMetadata,
  StreamBody,
  StringBody,
  SttpBackend
}
import sttp.model.{Header, HeaderNames, Method, StatusCode}

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

// TODO(ikhoon): Add HttpRequestEncoder, HttpResponseDecoder and test case
abstract class ArmeriaBackend[F[_], S](
    webClient: WebClient,
    private implicit val monad: MonadAsyncError[F],
    closeClient: Boolean
) extends SttpBackend[F, S, NothingT] {
  override def send[T](request: Request[T, S]): F[Response[T]] = {
    val value: F[HttpRequest] = monad.fromTry(Try(toArmeriaRequest(request)))
    value.flatMap(req => {
      val response = webClient.execute(req)
      fromArmeriaResponse(request.response, response)
    })
  }

  protected def streamBodyToPublisher[T <: HttpObject](s: S): Publisher[T]

  protected def inputStreamToPublisher(is: InputStream): Option[Publisher[HttpData]] = None

  protected def fileToPublisher(path: Path): Option[Publisher[HttpData]] = None

  def responseToBodyPublisher(p: Publisher[HttpObject]): F[(ResponseHeaders, Publisher[HttpData])] = {
    monad.async[(ResponseHeaders, Publisher[HttpData])](cb => {
      val publisher: Publisher[HttpData] = new Publisher[HttpData] { pub =>
        override def subscribe(s: Subscriber[_ >: HttpData]): Unit = {
          p.subscribe(new Subscriber[HttpObject] {
            var subscription: Subscription = _
            var headerReceived: Boolean = false

            override def onError(t: Throwable): Unit = {
              if (!headerReceived) {
                t match {
                  case _: CancelledSubscriptionException | _: AbortedStreamException =>
                    cb(Right((ResponseHeaders.of(HttpStatus.UNKNOWN), pub)))
                  case _ =>
                    cb(Left((t)))
                }
              }
              s.onError(t)
            }

            override def onComplete(): Unit = {
              if (!headerReceived) {
                cb(Right((ResponseHeaders.of(HttpStatus.UNKNOWN), pub)))
              }
              s.onComplete()
            }

            override def onNext(t: HttpObject): Unit = {
              t match {
                case data: HttpData =>
                  s.onNext(data)
                case headers: ResponseHeaders =>
                  headerReceived = true
                  if (headers.status().codeClass() != HttpStatusClass.INFORMATIONAL) {
                    cb(Right((headers, pub)))
                  }
                  subscription.request(1)
              }
            }

            override def onSubscribe(v: Subscription): Unit = {
              subscription = v
              s.onSubscribe(v)
            }
          })
        }
      }
    })
  }

  protected def fromArmeriaResponse[T](responseAs: ResponseAs[T, S], response: HttpResponse): F[Response[T]] = {
    responseToBodyPublisher(response)
      .flatMap {
        case (headers, publisher) =>
          val httpHeaders = headers
            .names()
            .asScala
            .flatMap(name => headers.getAll(name).asScala.map(Header.notValidated(name.toString, _)))
            .toList
          val meta =
            ResponseMetadata(httpHeaders,
                             StatusCode.notValidated(headers.status().code()),
                             headers.status().codeAsText())
          handleBody(publisher, responseAs, meta)
            .map(body => {
              client.Response(
                body,
                StatusCode.unsafeApply(headers.status().code()),
                headers.status.codeAsText(),
                httpHeaders,
                Nil
              )
            })
      }
  }

  private def handleBody[T](publisher: Publisher[HttpData],
                            responseAs: ResponseAs[T, S],
                            meta: ResponseMetadata): F[T] = {
    responseAs match {
      case MappedResponseAs(raw, g) =>
        val nested = handleBody(publisher, raw, meta)
        nested.map(g(_, meta))
      case ResponseAsFromMetadata(f) => handleBody(publisher, f(meta), meta)
      case _: ResponseAsStream[_, _] => monad.unit(publisherToStreamBody(publisher).asInstanceOf[T])
      case IgnoreResponse            =>
        // getting the body and discarding it
        publisherToBytes(publisher).map(_ => ())
      case ResponseAsByteArray =>
        publisherToBytes(publisher)
      case ResponseAsFile(file) =>
        publisherToFile(publisher, file.toFile).map(_ => file)
    }
  }

  protected def publisherToStreamBody(p: Publisher[HttpData]): S

  protected def publisherToBytes(p: Publisher[HttpData]): F[Array[Byte]] = {
    monad.async { cb =>
      def success(r: ByteBuffer): Unit = cb(Right(r.array()))

      def error(t: Throwable): Unit = cb(Left(t))

      p.subscribe(new SimpleSubscriber(success, error))
    }
  }

  protected def publisherToFile(p: Publisher[HttpData], f: File): F[Unit] = {
    publisherToBytes(p).map(bytes => FileHelpers.saveFile(f, new ByteArrayInputStream(bytes)))
  }

  private def toArmeriaRequest[T](request: Request[T, S]): HttpRequest = {
    val headers: RequestHeaders =
      headersToArmeria(request.headers, methodToArmeria(request.method), request.uri.toString)
    request.body match {
      case NoBody => HttpRequest.of(headers)
      case body: BasicRequestBody =>
        toArmeriaBody(body) match {
          case Left(httpData)   => HttpRequest.of(headers, httpData)
          case Right(publisher) => HttpRequest.of(headers, publisher)
        }
      case StreamBody(s) =>
        HttpRequest.of(headers, streamBodyToPublisher(s))
      case MultipartBody(parts) => {
        val boundary = newBoundary()
        val partsWithHeaders = parts.map { p =>
          val contentDisposition: String = s"${HeaderNames.ContentDisposition}: ${p.contentDispositionHeaderValue}"
          val otherHeaders: Seq[String] = p.headers.map(h => s"${h.name}: ${h.value}")
          val allHeaders: Seq[String] = contentDisposition +: otherHeaders
          (allHeaders.mkString(CrLf), p)
        }

        val dashes = "--"

        val dashesLen = dashes.length.toLong
        val crLfLen = CrLf.length.toLong
        val boundaryLen = boundary.length.toLong
        val finalBoundaryLen = dashesLen + boundaryLen + dashesLen + crLfLen

        val contentLength = partsWithHeaders
          .map {
            case (headers, p) =>
              val bodyLen: Option[Long] = p.body match {
                case StringBody(b, encoding, _) =>
                  Some(b.getBytes(encoding).length.toLong)
                case ByteArrayBody(b, _)   => Some(b.length.toLong)
                case ByteBufferBody(_, _)  => None
                case InputStreamBody(_, _) => None
                case FileBody(b, _)        => Some(b.toFile.length())
              }

              val headersLen = headers.getBytes(Iso88591).length

              bodyLen.map(bl => dashesLen + boundaryLen + crLfLen + headersLen + crLfLen + crLfLen + bl + crLfLen)
          }
          .foldLeft(Option(finalBoundaryLen)) {
            case (Some(acc), Some(l)) => Some(acc + l)
            case _                    => None
          }
        val builder = headers.toBuilder
        builder.add(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
        contentLength.foreach { cl =>
          builder.add(HttpHeaderNames.CONTENT_LENGTH, cl.toString)
        }
        val newHeaders = builder.build()

        val writer = HttpRequest.streaming(newHeaders)
        partsWithHeaders.foreach {
          case (headers, p) =>
            toArmeriaBody(p.body) match {
              case Left(httpData) =>
                val body = Array(dashes, boundary, CrLf, headers, CrLf, CrLf)
                  .flatMap(_.getBytes(Iso88591)) ++ httpData.array() ++ CrLf.getBytes(Iso88591)
                writer.write(HttpData.wrap(body))
              case Right(publisher) =>
                // TODO(ikhoon): Ensure data order, SequencialPublisher
                publisher.subscribe(new StreamingSubscriber(writer))
            }
        }
        writer
      }
    }
  }

  private def toArmeriaBody(body: BasicRequestBody): Either[HttpData, Publisher[HttpData]] =
    body match {
      case StringBody(s, e, _) =>
        Left(HttpData.wrap(s.getBytes(e)))
      case ByteArrayBody(b, _) =>
        Left(HttpData.wrap(b))
      case ByteBufferBody(b, _) =>
        Left(HttpData.wrap(Unpooled.wrappedBuffer(b)))
      case InputStreamBody(is, _) =>
        inputStreamToPublisher(is).toRight {
          val bytes = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
          HttpData.wrap(bytes)
        }
      case FileBody(b, _) =>
        fileToPublisher(b.toPath).toRight {
          val buf = Unpooled.wrappedBuffer(Files.readAllBytes(b.toPath))
          HttpData.wrap(buf)
        }
    }

  private def methodToArmeria(method: Method): HttpMethod =
    method match {
      case Method.GET     => HttpMethod.GET
      case Method.HEAD    => HttpMethod.HEAD
      case Method.POST    => HttpMethod.POST
      case Method.PUT     => HttpMethod.PUT
      case Method.DELETE  => HttpMethod.DELETE
      case Method.OPTIONS => HttpMethod.OPTIONS
      case Method.PATCH   => HttpMethod.PATCH
      case Method.CONNECT => HttpMethod.CONNECT
      case Method.TRACE   => HttpMethod.TRACE
    }

  private def headersToArmeria(headers: Seq[Header], httpMethod: HttpMethod, path: String) =
    headers
      .foldLeft(RequestHeaders.builder(httpMethod, path)) {
        case (builder, header) => builder.add(header.name, header.value)
      }
      .build()

  val rand = ThreadLocalRandom.current()

  val dashes = "--"

  // TODO(ikhoon) fix this
  private def newBoundary(): String = rand.nextLong().toHexString
}

/**
  * A [[Subscriber]] implementation which writes a streaming response with the contents converted from
  * the objects published from a publisher.
  */
final private class StreamingSubscriber(writer: HttpRequestWriter) extends Subscriber[HttpData] {
  private var subscription: Subscription = _
  private var headersSent = false

  override def onSubscribe(s: Subscription): Unit = {
    assert(subscription == null)
    subscription = s
    writer.completionFuture().exceptionally {
      new java.util.function.Function[Throwable, Void] {
        override def apply(t: Throwable): Void = {
          s.cancel()
          null
        }
      }
    }
    s.request(Long.MaxValue)
  }

  override def onNext(value: HttpData): Unit =
    if (writer.isOpen) {
      try {
        writer.write(value)
      } catch {
        case NonFatal(e) => onError(e)
      }
    }

  override def onError(cause: Throwable): Unit =
    if (writer.isOpen) {
      try writer.close(cause)
      catch {
        case NonFatal(_) =>
          // 'subscription.cancel()' would be called by the close future listener of the writer,
          // so we call it when we failed to close the writer.
          assert(subscription != null)
          subscription.cancel()
      }
    }

  override def onComplete(): Unit =
    if (writer.isOpen) {
      writer.close()
    }
}

private class SimpleSubscriber(success: ByteBuffer => Unit, error: Throwable => Unit) extends Subscriber[HttpData] {
  private val chunks = new ConcurrentLinkedQueue[Array[Byte]]()
  private var subscription: Subscription = _
  private var size = 0

  override def onSubscribe(s: Subscription): Unit = {
    assert(s != null)
    if (this.subscription != null) {
      s.cancel() // Cancel the additional subscription
    } else {
      subscription = s
      subscription.request(Long.MaxValue)
    }
  }

  @silent("discarded")
  override def onNext(b: HttpData): Unit = {
    assert(b != null)
    val a = b.array()
    size += a.length
    chunks.add(a)
  }

  override def onError(t: Throwable): Unit = {
    assert(t != null)
    chunks.clear()
    error(t)
  }

  override def onComplete(): Unit = {
    val result = ByteBuffer.allocate(size)
    chunks.asScala.foreach(result.put)
    chunks.clear()
    success(result)
  }
}
