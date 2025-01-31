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
package io.netty.handler.codec.spdy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.spdy.SpdyHttpHeaders.Names;

import java.util.List;
import java.util.Map;

import static io.netty.handler.codec.spdy.SpdyHeaders.HttpNames.*;

/**
 * Encodes {@link HttpRequest}s, {@link HttpResponse}s, and {@link HttpContent}s
 * into {@link SpdySynStreamFrame}s and {@link SpdySynReplyFrame}s.
 *
 * <h3>Request Annotations</h3>
 *
 * SPDY specific headers must be added to {@link HttpRequest}s:
 * <table border=1>
 * <tr>
 * <th>Header Name</th><th>Header Value</th>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Stream-ID"}</td>
 * <td>The Stream-ID for this request.
 * Stream-IDs must be odd, positive integers, and must increase monotonically.</td>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Priority"}</td>
 * <td>The priority value for this request.
 * The priority should be between 0 and 7 inclusive.
 * 0 represents the highest priority and 7 represents the lowest.
 * This header is optional and defaults to 0.</td>
 * </tr>
 * </table>
 *
 * <h3>Response Annotations</h3>
 *
 * SPDY specific headers must be added to {@link HttpResponse}s:
 * <table border=1>
 * <tr>
 * <th>Header Name</th><th>Header Value</th>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Stream-ID"}</td>
 * <td>The Stream-ID of the request corresponding to this response.</td>
 * </tr>
 * </table>
 *
 * <h3>Pushed Resource Annotations</h3>
 *
 * SPDY specific headers must be added to pushed {@link HttpResponse}s:
 * <table border=1>
 * <tr>
 * <th>Header Name</th><th>Header Value</th>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Stream-ID"}</td>
 * <td>The Stream-ID for this resource.
 * Stream-IDs must be even, positive integers, and must increase monotonically.</td>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Associated-To-Stream-ID"}</td>
 * <td>The Stream-ID of the request that initiated this pushed resource.</td>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-Priority"}</td>
 * <td>The priority value for this resource.
 * The priority should be between 0 and 7 inclusive.
 * 0 represents the highest priority and 7 represents the lowest.
 * This header is optional and defaults to 0.</td>
 * </tr>
 * <tr>
 * <td>{@code "X-SPDY-URL"}</td>
 * <td>The absolute path for the resource being pushed.</td>
 * </tr>
 * </table>
 *
 * <h3>Required Annotations</h3>
 *
 * SPDY requires that all Requests and Pushed Resources contain
 * an HTTP "Host" header.
 *
 * <h3>Optional Annotations</h3>
 *
 * Requests and Pushed Resources must contain a SPDY scheme header.
 * This can be set via the {@code "X-SPDY-Scheme"} header but otherwise
 * defaults to "https" as that is the most common SPDY deployment.
 *
 * <h3>Chunked Content</h3>
 *
 * This encoder associates all {@link HttpContent}s that it receives
 * with the most recently received 'chunked' {@link HttpRequest}
 * or {@link HttpResponse}.
 *
 * <h3>Pushed Resources</h3>
 *
 * All pushed resources should be sent before sending the response
 * that corresponds to the initial request.
 */
public class SpdyHttpEncoder extends MessageToMessageEncoder<HttpObject> {

    private final int spdyVersion;
    private int currentStreamId;

    /**
     * Creates a new instance.
     *
     * @param version the protocol version
     */
    public SpdyHttpEncoder(SpdyVersion version) {
        if (version == null) {
            throw new NullPointerException("version");
        }
        spdyVersion = version.getVersion();
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {

        boolean valid = false;
        boolean last = false;

        if (msg instanceof HttpRequest) {

            HttpRequest httpRequest = (HttpRequest) msg;
            SpdySynStreamFrame spdySynStreamFrame = createSynStreamFrame(httpRequest);
            out.add(spdySynStreamFrame);

            last = spdySynStreamFrame.isLast();
            valid = true;
        }
        if (msg instanceof HttpResponse) {

            HttpResponse httpResponse = (HttpResponse) msg;
            if (httpResponse.headers().contains(SpdyHttpHeaders.Names.ASSOCIATED_TO_STREAM_ID)) {
                SpdySynStreamFrame spdySynStreamFrame = createSynStreamFrame(httpResponse);
                last = spdySynStreamFrame.isLast();
                out.add(spdySynStreamFrame);
            } else {
                SpdySynReplyFrame spdySynReplyFrame = createSynReplyFrame(httpResponse);
                last = spdySynReplyFrame.isLast();
                out.add(spdySynReplyFrame);
            }

            valid = true;
        }
        if (msg instanceof HttpContent && !last) {

            HttpContent chunk = (HttpContent) msg;

            chunk.content().retain();
            SpdyDataFrame spdyDataFrame = new DefaultSpdyDataFrame(currentStreamId, chunk.content());
            spdyDataFrame.setLast(chunk instanceof LastHttpContent);
            if (chunk instanceof LastHttpContent) {
                LastHttpContent trailer = (LastHttpContent) chunk;
                HttpHeaders trailers = trailer.trailingHeaders();
                if (trailers.isEmpty()) {
                    out.add(spdyDataFrame);
                } else {
                    // Create SPDY HEADERS frame out of trailers
                    SpdyHeadersFrame spdyHeadersFrame = new DefaultSpdyHeadersFrame(currentStreamId);
                    for (Map.Entry<String, String> entry: trailers) {
                        spdyHeadersFrame.headers().add(entry.getKey(), entry.getValue());
                    }

                    // Write HEADERS frame and append Data Frame
                    out.add(spdyHeadersFrame);
                    out.add(spdyDataFrame);
                }
            } else {
                out.add(spdyDataFrame);
            }

            valid = true;
        }

        if (!valid) {
            throw new UnsupportedMessageTypeException(msg);
        }
    }

    private SpdySynStreamFrame createSynStreamFrame(HttpMessage httpMessage)
            throws Exception {
        // Get the Stream-ID, Associated-To-Stream-ID, Priority, URL, and scheme from the headers
        final HttpHeaders httpHeaders = httpMessage.headers();
        int streamID = HttpHeaders.getIntHeader(httpMessage, Names.STREAM_ID);
        int associatedToStreamId = HttpHeaders.getIntHeader(httpMessage, Names.ASSOCIATED_TO_STREAM_ID, 0);
        byte priority = (byte) HttpHeaders.getIntHeader(httpMessage, Names.PRIORITY, 0);
        String URL = httpHeaders.get(Names.URL);
        String scheme = httpHeaders.get(Names.SCHEME);
        httpHeaders.remove(Names.STREAM_ID);
        httpHeaders.remove(Names.ASSOCIATED_TO_STREAM_ID);
        httpHeaders.remove(Names.PRIORITY);
        httpHeaders.remove(Names.URL);
        httpHeaders.remove(Names.SCHEME);

        // The Connection, Keep-Alive, Proxy-Connection, and Transfer-Encoding
        // headers are not valid and MUST not be sent.
        httpHeaders.remove(HttpHeaders.Names.CONNECTION);
        httpHeaders.remove("Keep-Alive");
        httpHeaders.remove("Proxy-Connection");
        httpHeaders.remove(HttpHeaders.Names.TRANSFER_ENCODING);

        SpdySynStreamFrame spdySynStreamFrame =
                new DefaultSpdySynStreamFrame(streamID, associatedToStreamId, priority);

        // Unfold the first line of the message into name/value pairs
        SpdyHeaders frameHeaders = spdySynStreamFrame.headers();
        if (httpMessage instanceof FullHttpRequest) {
            HttpRequest httpRequest = (HttpRequest) httpMessage;
            frameHeaders.setObject(METHOD, httpRequest.method());
            frameHeaders.set(PATH, httpRequest.uri());
            frameHeaders.setObject(VERSION, httpMessage.protocolVersion());
        }
        if (httpMessage instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpMessage;
            frameHeaders.setInt(STATUS, httpResponse.status().code());
            frameHeaders.set(PATH, URL);
            frameHeaders.setObject(VERSION, httpMessage.protocolVersion());
            spdySynStreamFrame.setUnidirectional(true);
        }

        // Replace the HTTP host header with the SPDY host header
        if (spdyVersion >= 3) {
            String host = HttpHeaders.getHost(httpMessage);
            httpHeaders.remove(HttpHeaders.Names.HOST);
            frameHeaders.set(HOST, host);
        }

        // Set the SPDY scheme header
        if (scheme == null) {
            scheme = "https";
        }
        frameHeaders.set(SCHEME, scheme);

        // Transfer the remaining HTTP headers
        for (Map.Entry<String, String> entry: httpHeaders) {
            frameHeaders.add(entry.getKey(), entry.getValue());
        }
        currentStreamId = spdySynStreamFrame.streamId();
        spdySynStreamFrame.setLast(isLast(httpMessage));

        return spdySynStreamFrame;
    }

    private SpdySynReplyFrame createSynReplyFrame(HttpResponse httpResponse)
            throws Exception {
        // Get the Stream-ID from the headers
        final HttpHeaders httpHeaders = httpResponse.headers();
        int streamID = HttpHeaders.getIntHeader(httpResponse, Names.STREAM_ID);
        httpHeaders.remove(Names.STREAM_ID);

        // The Connection, Keep-Alive, Proxy-Connection, and Transfer-Encoding
        // headers are not valid and MUST not be sent.
        httpHeaders.remove(HttpHeaders.Names.CONNECTION);
        httpHeaders.remove("Keep-Alive");
        httpHeaders.remove("Proxy-Connection");
        httpHeaders.remove(HttpHeaders.Names.TRANSFER_ENCODING);

        SpdySynReplyFrame spdySynReplyFrame = new DefaultSpdySynReplyFrame(streamID);
        SpdyHeaders frameHeaders = spdySynReplyFrame.headers();
        // Unfold the first line of the response into name/value pairs
        frameHeaders.setInt(STATUS, httpResponse.status().code());
        frameHeaders.setObject(VERSION, httpResponse.protocolVersion());

        // Transfer the remaining HTTP headers
        for (Map.Entry<String, String> entry: httpHeaders) {
            spdySynReplyFrame.headers().add(entry.getKey(), entry.getValue());
        }

        currentStreamId = streamID;
        spdySynReplyFrame.setLast(isLast(httpResponse));

        return spdySynReplyFrame;
    }

    /**
     * Checks if the given HTTP message should be considered as a last SPDY frame.
     *
     * @param httpMessage check this HTTP message
     * @return whether the given HTTP message should generate a <em>last</em> SPDY frame.
     */
    private static boolean isLast(HttpMessage httpMessage) {
        if (httpMessage instanceof FullHttpMessage) {
            FullHttpMessage fullMessage = (FullHttpMessage) httpMessage;
            if (fullMessage.trailingHeaders().isEmpty() && !fullMessage.content().isReadable()) {
                return true;
            }
        }

        return false;
    }
}
