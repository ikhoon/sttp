package sttp.client.armeria

import java.nio.file.Files
import java.util.concurrent.ThreadLocalRandom

import com.github.ghik.silencer.silent
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.stream.StreamWriter
import com.linecorp.armeria.common.{HttpData, HttpHeaderNames, HttpMethod, HttpObject, HttpRequest, HttpRequestWriter, RequestHeaders}
import io.netty.buffer.{ByteBuf, Unpooled}
import org.reactivestreams.Publisher
import sttp.client.internal.{CrLf, Iso88591}
import sttp.client.monad.MonadAsyncError
import sttp.client.{BasicRequestBody, ByteArrayBody, ByteBufferBody, FileBody, InputStreamBody, MultipartBody, NoBody, NothingT, Request, RequestBody, Response, StreamBody, StringBody, SttpBackend}
import sttp.model.{Header, HeaderNames, Method}

import scala.util.Random

abstract class ArmeriaBackend[F[_], S](
                                        webClient: WebClient,
                                        private implicit val monad: MonadAsyncError[F],
                                        closeClient: Boolean
                                      ) extends SttpBackend[F, S, NothingT] {
  override def send[T](request: Request[T, S]): F[Response[T]] = {
    ???
  }

  protected def streamBodyToPublisher[T <: HttpObject](s: S): Publisher[T]

  private def toArmeriaRequest[T](request: Request[T, S]): HttpRequest = {
    val headers: RequestHeaders = headersToArmeria(request.headers, methodToArmeria(request.method), request.uri.toString)
    request.body match {
      case NoBody => HttpRequest.of(headers)
      case StringBody(s, e, _) =>
        HttpRequest.of(headers, HttpData.wrap(s.getBytes(e)))
      case ByteArrayBody(b, _) =>
        HttpRequest.of(headers, HttpData.wrap(b))
      case ByteBufferBody(b, _) =>
        HttpRequest.of(headers, HttpData.wrap(Unpooled.wrappedBuffer(b)))
      case InputStreamBody(is, _) =>
        val bytes = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
        HttpRequest.of(headers, HttpData.wrap(bytes))
      case FileBody(b, _) =>
        // TODO(ikhoon) covert to reactive stream?
        val buf = Unpooled.wrappedBuffer(Files.readAllBytes(b.toPath))
        HttpRequest.of(headers, HttpData.wrap(buf))
      case StreamBody(s) =>
        HttpRequest.of(headers, streamBodyToPublisher(s))
      case MultipartBody(parts) => {
        val boundary = newBoundary()

        val partsWithHeaders = parts.map { p =>
          val contentDisposition: String = s"${HeaderNames.ContentDisposition}: ${p.contentDispositionHeaderValue}"
          val otherHeaders: List[String] = p.headers.map(h => s"${h.name}: ${h.value}")
          val allHeaders: List[String] = contentDisposition :: otherHeaders
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

      }
    }
  }

  private def writeBasicBody(writer: HttpRequestWriter, body: BasicRequestBody) = {
    case StringBody(s, e, _) =>
      writer.write(HttpData.wrap(s.getBytes(e)))
    case ByteArrayBody(b, _) =>
      writer.write(HttpData.wrap(b))
    case ByteBufferBody(b, _) =>
      writer.write(HttpData.wrap(Unpooled.wrappedBuffer(b)))
    case InputStreamBody(is, _) =>
      val bytes = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
      writer.write(HttpData.wrap(bytes))
    case FileBody(b, _) =>
      // TODO(ikhoon) covert to reactive stream?
      val buf = Unpooled.wrappedBuffer(Files.readAllBytes(b.toPath))
      writer.write(HttpData.wrap(buf))
  }

  private def methodToArmeria(method: Method): HttpMethod =
    method match {
      case Method.GET => HttpMethod.GET
      case Method.HEAD => HttpMethod.HEAD
      case Method.POST => HttpMethod.POST
      case Method.PUT => HttpMethod.PUT
      case Method.DELETE => HttpMethod.DELETE
      case Method.OPTIONS => HttpMethod.OPTIONS
      case Method.PATCH => HttpMethod.PATCH
      case Method.CONNECT => HttpMethod.CONNECT
      case Method.TRACE => HttpMethod.TRACE
    }

  private def headersToArmeria(headers: Seq[Header], httpMethod: HttpMethod, path: String) =
    headers.foldLeft(RequestHeaders.builder(httpMethod, path)) {
      case (builder, header) => builder.add(header.name, header.value)
    }.build()

  val rand = ThreadLocalRandom.current()

  val dashes = "--"

  private def newBoundary(): String = String.format("%016x", rand.nextLong())



}