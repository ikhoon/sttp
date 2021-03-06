package sttp.client3.asynchttpclient

import sttp.client3.testing.websocket.{WebSocketBufferOverflowTest, WebSocketStreamingTest, WebSocketTest}

abstract class AsyncHttpClientWebSocketTest[F[_], S]
    extends WebSocketTest[F]
    with WebSocketBufferOverflowTest[F]
    with WebSocketStreamingTest[F, S] {

  override def throwsWhenNotAWebSocket: Boolean = true

  override def bufferCapacity: Int = AsyncHttpClientBackend.DefaultWebSocketBufferCapacity.get
}
