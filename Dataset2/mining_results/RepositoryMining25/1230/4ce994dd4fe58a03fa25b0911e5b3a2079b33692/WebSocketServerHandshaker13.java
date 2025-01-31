/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http.websocketx;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpVersion.*;

/**
 * <p>
 * Performs server side opening and closing handshakes for <a href="http://netty.io/s/rfc6455">RFC 6455</a>
 * (originally web socket specification <a href="http://netty.io/s/ws-17">draft-ietf-hybi-thewebsocketprotocol-17</a>).
 * </p>
 */
public class WebSocketServerHandshaker13 extends WebSocketServerHandshaker {

    private static final CharSequence WEBSOCKET = Values.WEBSOCKET.toLowerCase();

    public static final String WEBSOCKET_13_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final boolean allowExtensions;
    private final boolean allowMaskMismatch;

    /**
     * Constructor specifying the destination web socket location
     *
     * @param webSocketURL
     *        URL for web socket communications. e.g "ws://myhost.com/mypath". Subsequent web
     *        socket frames will be sent to this URL.
     * @param subprotocols
     *        CSV of supported protocols
     * @param allowExtensions
     *        Allow extensions to be used in the reserved bits of the web socket frame
     * @param maxFramePayloadLength
     *        Maximum allowable frame payload length. Setting this value to your application's
     *        requirement may reduce denial of service attacks using long data frames.
     */
    public WebSocketServerHandshaker13(
            String webSocketURL, String subprotocols, boolean allowExtensions, int maxFramePayloadLength) {
        this(webSocketURL, subprotocols, allowExtensions, maxFramePayloadLength, false);
    }

    /**
     * Constructor specifying the destination web socket location
     *
     * @param webSocketURL
     *        URL for web socket communications. e.g "ws://myhost.com/mypath". Subsequent web
     *        socket frames will be sent to this URL.
     * @param subprotocols
     *        CSV of supported protocols
     * @param allowExtensions
     *        Allow extensions to be used in the reserved bits of the web socket frame
     * @param maxFramePayloadLength
     *        Maximum allowable frame payload length. Setting this value to your application's
     *        requirement may reduce denial of service attacks using long data frames.
     * @param allowMaskMismatch
     *            Allows to loosen the masking requirement on received frames. When this is set to false then also
     *            frames which are not masked properly according to the standard will still be accepted.
     */
    public WebSocketServerHandshaker13(
            String webSocketURL, String subprotocols, boolean allowExtensions, int maxFramePayloadLength,
            boolean allowMaskMismatch) {
        super(WebSocketVersion.V13, webSocketURL, subprotocols, maxFramePayloadLength);
        this.allowExtensions = allowExtensions;
        this.allowMaskMismatch = allowMaskMismatch;
    }

    /**
     * <p>
     * Handle the web socket handshake for the web socket specification <a href=
     * "http://tools.ietf.org/html/draft-ietf-hybi-thewebsocketprotocol-17">HyBi versions 13-17</a>. Versions 13-17
     * share the same wire protocol.
     * </p>
     *
     * <p>
     * Browser request to the server:
     * </p>
     *
     * <pre>
     * GET /chat HTTP/1.1
     * Host: server.example.com
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
     * Sec-WebSocket-Origin: http://example.com
     * Sec-WebSocket-Protocol: chat, superchat
     * Sec-WebSocket-Version: 13
     * </pre>
     *
     * <p>
     * Server response:
     * </p>
     *
     * <pre>
     * HTTP/1.1 101 Switching Protocols
     * Upgrade: websocket
     * Connection: Upgrade
     * Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
     * Sec-WebSocket-Protocol: chat
     * </pre>
     */
    @Override
    protected FullHttpResponse newHandshakeResponse(FullHttpRequest req, HttpHeaders headers) {
        FullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
        if (headers != null) {
            res.headers().add(headers);
        }

        CharSequence key = req.headers().get(Names.SEC_WEBSOCKET_KEY);
        if (key == null) {
            throw new WebSocketHandshakeException("not a WebSocket request: missing key");
        }
        String acceptSeed = key + WEBSOCKET_13_ACCEPT_GUID;
        byte[] sha1 = WebSocketUtil.sha1(acceptSeed.getBytes(CharsetUtil.US_ASCII));
        String accept = WebSocketUtil.base64(sha1);

        if (logger.isDebugEnabled()) {
            logger.debug("WebSocket version 13 server handshake key: {}, response: {}", key, accept);
        }

        res.headers().add(Names.UPGRADE, WEBSOCKET);
        res.headers().add(Names.CONNECTION, Names.UPGRADE);
        res.headers().add(Names.SEC_WEBSOCKET_ACCEPT, accept);
        String subprotocols = req.headers().get(Names.SEC_WEBSOCKET_PROTOCOL);
        if (subprotocols != null) {
            String selectedSubprotocol = selectSubprotocol(subprotocols);
            if (selectedSubprotocol == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Requested subprotocol(s) not supported: {}", subprotocols);
                }
            } else {
                res.headers().add(Names.SEC_WEBSOCKET_PROTOCOL, selectedSubprotocol);
            }
        }
        return res;
    }

    @Override
    protected WebSocketFrameDecoder newWebsocketDecoder() {
        return new WebSocket13FrameDecoder(true, allowExtensions, maxFramePayloadLength(), allowMaskMismatch);
    }

    @Override
    protected WebSocketFrameEncoder newWebSocketEncoder() {
        return new WebSocket13FrameEncoder(false);
    }
}
