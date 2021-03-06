package sttp.client3.httpclient

import sttp.capabilities.WebSockets
import sttp.client3.testing.ConvertToFuture
import sttp.client3.testing.websocket.WebSocketTest
import sttp.client3.SttpBackend
import sttp.monad.{FutureMonad, MonadError}

import scala.concurrent.Future

class HttpClientFutureWebSocketTest[F[_]] extends WebSocketTest[Future] {
  override val backend: SttpBackend[Future, WebSockets] = HttpClientFutureBackend()
  override implicit val convertToFuture: ConvertToFuture[Future] = ConvertToFuture.future
  override implicit val monad: MonadError[Future] = new FutureMonad()
}
