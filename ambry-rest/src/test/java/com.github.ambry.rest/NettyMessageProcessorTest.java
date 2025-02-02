/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.rest;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.config.NettyConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.notification.BlobReplicaSourceType;
import com.github.ambry.notification.NotificationSystem;
import com.github.ambry.router.InMemoryRouter;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;


/**
 * Unit tests for {@link NettyMessageProcessor}.
 */
public class NettyMessageProcessorTest {
  private final InMemoryRouter router;
  private final BlobStorageService blobStorageService;
  private final MockRestRequestResponseHandler requestHandler;
  private final HelperNotificationSystem notificationSystem = new HelperNotificationSystem();

  private static final AtomicLong requestIdGenerator = new AtomicLong(0);

  /**
   * Sets up the mock services that {@link NettyMessageProcessor} can use.
   * @throws InstantiationException
   * @throws IOException
   */
  public NettyMessageProcessorTest()
      throws InstantiationException, IOException {
    VerifiableProperties verifiableProperties = new VerifiableProperties(new Properties());
    RestRequestMetricsTracker.setDefaults(new MetricRegistry());
    router = new InMemoryRouter(verifiableProperties, notificationSystem);
    requestHandler = new MockRestRequestResponseHandler();
    blobStorageService = new MockBlobStorageService(verifiableProperties, requestHandler, router);
    requestHandler.setBlobStorageService(blobStorageService);
    blobStorageService.start();
    requestHandler.start();
  }

  /**
   * Clean up task.
   */
  @After
  public void cleanUp()
      throws IOException {
    blobStorageService.shutdown();
    router.close();
    notificationSystem.close();
  }

  /**
   * Tests for the common case request handling flow.
   * @throws IOException
   */
  @Test
  public void requestHandleWithGoodInputTest()
      throws IOException {
    doRequestHandleWithoutKeepAlive(HttpMethod.GET, RestMethod.GET);
    doRequestHandleWithoutKeepAlive(HttpMethod.DELETE, RestMethod.DELETE);
    doRequestHandleWithoutKeepAlive(HttpMethod.HEAD, RestMethod.HEAD);

    EmbeddedChannel channel = createChannel();
    doRequestHandleWithKeepAlive(channel, HttpMethod.GET, RestMethod.GET);
    doRequestHandleWithKeepAlive(channel, HttpMethod.DELETE, RestMethod.DELETE);
    doRequestHandleWithKeepAlive(channel, HttpMethod.HEAD, RestMethod.HEAD);
  }

  /**
   * Tests the case where raw bytes are POSTed as chunks.
   * @throws InterruptedException
   */
  @Test
  public void rawBytesPostTest()
      throws InterruptedException {
    Random random = new Random();
    // request also contains content.
    ByteBuffer content = ByteBuffer.wrap(RestTestUtils.getRandomBytes(random.nextInt(128) + 128));
    HttpRequest postRequest =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/", Unpooled.wrappedBuffer(content));
    HttpHeaders.setHeader(postRequest, RestUtils.Headers.SERVICE_ID, "rawBytesPostTest");
    HttpHeaders.setHeader(postRequest, RestUtils.Headers.BLOB_SIZE, content.remaining());
    postRequest = ReferenceCountUtil.retain(postRequest);
    ByteBuffer receivedContent = doPostTest(postRequest, null);
    compareContent(receivedContent, Collections.singletonList(content));

    // request and content separate.
    final int NUM_CONTENTS = 5;
    postRequest = RestTestUtils.createRequest(HttpMethod.POST, "/", null);
    List<ByteBuffer> contents = new ArrayList<ByteBuffer>(NUM_CONTENTS);
    int blobSize = 0;
    for (int i = 0; i < NUM_CONTENTS; i++) {
      ByteBuffer buffer = ByteBuffer.wrap(RestTestUtils.getRandomBytes(random.nextInt(128) + 128));
      blobSize += buffer.remaining();
      contents.add(i, buffer);
    }
    HttpHeaders.setHeader(postRequest, RestUtils.Headers.SERVICE_ID, "rawBytesPostTest");
    HttpHeaders.setHeader(postRequest, RestUtils.Headers.BLOB_SIZE, blobSize);
    receivedContent = doPostTest(postRequest, contents);
    compareContent(receivedContent, contents);
  }

  /**
   * Tests the case where multipart upload is used.
   * @throws Exception
   */
  @Test
  public void multipartPostTest()
      throws Exception {
    Random random = new Random();
    ByteBuffer content = ByteBuffer.wrap(RestTestUtils.getRandomBytes(random.nextInt(128) + 128));
    HttpRequest httpRequest = RestTestUtils.createRequest(HttpMethod.POST, "/", null);
    HttpHeaders.setHeader(httpRequest, RestUtils.Headers.SERVICE_ID, "rawBytesPostTest");
    HttpHeaders.setHeader(httpRequest, RestUtils.Headers.BLOB_SIZE, content.remaining());
    HttpPostRequestEncoder encoder = createEncoder(httpRequest, content);
    HttpRequest postRequest = encoder.finalizeRequest();
    List<ByteBuffer> contents = new ArrayList<ByteBuffer>();
    while (!encoder.isEndOfInput()) {
      // Sending null for ctx because the encoder is OK with that.
      contents.add(encoder.readChunk(null).content().nioBuffer());
    }
    ByteBuffer receivedContent = doPostTest(postRequest, contents);
    compareContent(receivedContent, Collections.singletonList(content));
  }

  /**
   * Tests for error handling flow when bad input streams are provided to the {@link NettyMessageProcessor}.
   */
  @Test
  public void requestHandleWithBadInputTest()
      throws IOException {
    String content = "@@randomContent@@@";
    // content without request.
    EmbeddedChannel channel = createChannel();
    channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(content.getBytes())));
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.BAD_REQUEST, response.getStatus());

    // content without request on a channel that was kept alive
    channel = createChannel();
    // send and receive response for a good request and keep the channel alive
    channel.writeInbound(RestTestUtils.createRequest(HttpMethod.GET, MockBlobStorageService.ECHO_REST_METHOD, null));
    channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    // drain the content
    while (channel.readOutbound() != null) {
      ;
    }
    assertTrue("Channel is not active", channel.isActive());
    // send content without request
    channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.BAD_REQUEST, response.getStatus());

    // content when no content is expected.
    channel = createChannel();
    channel.writeInbound(RestTestUtils.createRequest(HttpMethod.GET, "/", null));
    channel.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer(content.getBytes())));
    response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.BAD_REQUEST, response.getStatus());

    // wrong HTTPObject.
    channel = createChannel();
    channel.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
    response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.BAD_REQUEST, response.getStatus());
  }

  /**
   * Tests for error handling flow when the {@link RestRequestHandler} throws exceptions.
   */
  @Test
  public void requestHandlerExceptionTest() {
    try {
      // RuntimeException
      Properties properties = new Properties();
      properties.setProperty(MockRestRequestResponseHandler.RUNTIME_EXCEPTION_ON_HANDLE, "true");
      requestHandler.breakdown(new VerifiableProperties(properties));
      doRequestHandlerExceptionTest(HttpMethod.GET, HttpResponseStatus.INTERNAL_SERVER_ERROR);

      // RestServiceException
      properties.clear();
      properties.setProperty(MockRestRequestResponseHandler.REST_EXCEPTION_ON_HANDLE,
          RestServiceErrorCode.InternalServerError.toString());
      requestHandler.breakdown(new VerifiableProperties(properties));
      doRequestHandlerExceptionTest(HttpMethod.GET, HttpResponseStatus.INTERNAL_SERVER_ERROR);

      // ClosedChannelException
      properties.clear();
      properties.setProperty(MockRestRequestResponseHandler.CLOSE_REQUEST_ON_HANDLE, "true");
      requestHandler.breakdown(new VerifiableProperties(properties));
      doRequestHandlerExceptionTest(HttpMethod.POST, HttpResponseStatus.INTERNAL_SERVER_ERROR);
    } finally {
      requestHandler.fix();
    }
  }

  // helpers
  // general

  /**
   * Creates an {@link EmbeddedChannel} that incorporates an instance of {@link NettyMessageProcessor}.
   * @return an {@link EmbeddedChannel} that incorporates an instance of {@link NettyMessageProcessor}.
   */
  private EmbeddedChannel createChannel() {
    NettyMetrics nettyMetrics = new NettyMetrics(new MetricRegistry());
    NettyConfig nettyConfig = new NettyConfig(new VerifiableProperties(new Properties()));
    NettyMessageProcessor processor = new NettyMessageProcessor(nettyMetrics, nettyConfig, requestHandler);
    return new EmbeddedChannel(new ChunkedWriteHandler(), processor);
  }

  /**
   * Sends the provided {@code httpRequest} and verifies that the response is an echo of the {@code restMethod}.
   * @param channel the {@link EmbeddedChannel} to send the request over.
   * @param httpMethod the {@link HttpMethod} for the request.
   * @param restMethod the equivalent {@link RestMethod} for {@code httpMethod}. Used to check for correctness of
   *                   response.
   * @param isKeepAlive if the request needs to be keep-alive.
   * @throws IOException
   */
  private void sendRequestCheckResponse(EmbeddedChannel channel, HttpMethod httpMethod, RestMethod restMethod,
      boolean isKeepAlive)
      throws IOException {
    long requestId = requestIdGenerator.getAndIncrement();
    String uri = MockBlobStorageService.ECHO_REST_METHOD + requestId;
    HttpRequest httpRequest = RestTestUtils.createRequest(httpMethod, uri, null);
    HttpHeaders.setKeepAlive(httpRequest, isKeepAlive);
    channel.writeInbound(httpRequest);
    channel.writeInbound(new DefaultLastHttpContent());
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", HttpResponseStatus.OK, response.getStatus());
    // MockBlobStorageService echoes the RestMethod + request id.
    String expectedResponse = restMethod.toString() + requestId;
    assertEquals("Unexpected content", expectedResponse,
        RestTestUtils.getContentString((HttpContent) channel.readOutbound()));
    assertTrue("End marker was expected", channel.readOutbound() instanceof LastHttpContent);
  }

  /**
   * Does the post test by sending the request and content to {@link NettyMessageProcessor} through an
   * {@link EmbeddedChannel} and returns the data stored in the {@link InMemoryRouter} as a result of the post.
   * @param postRequest the POST request as a {@link HttpRequest}.
   * @param contentToSend the content to be sent as a part of the POST.
   * @return the data stored in the {@link InMemoryRouter} as a result of the POST.
   * @throws InterruptedException
   */
  private ByteBuffer doPostTest(HttpRequest postRequest, List<ByteBuffer> contentToSend)
      throws InterruptedException {
    EmbeddedChannel channel = createChannel();

    // POST
    notificationSystem.reset();
    HttpHeaders.setHeader(postRequest, RestUtils.Headers.AMBRY_CONTENT_TYPE, "application/octet-stream");
    HttpHeaders.setKeepAlive(postRequest, false);
    channel.writeInbound(postRequest);
    if (contentToSend != null) {
      for (ByteBuffer content : contentToSend) {
        channel.writeInbound(new DefaultHttpContent(Unpooled.wrappedBuffer(content)));
      }
      channel.writeInbound(LastHttpContent.EMPTY_LAST_CONTENT);
    }
    if (!notificationSystem.operationCompleted.await(100, TimeUnit.MILLISECONDS)) {
      fail("Post did not succeed after 100ms. There is an error or timeout needs to increase");
    }
    assertNotNull("Blob id operated on cannot be null", notificationSystem.blobIdOperatedOn);
    assertTrue("Channel should be active", channel.isActive());
    return router.getActiveBlobs().get(notificationSystem.blobIdOperatedOn).getBlob();
  }

  /**
   * Compares {@code contentToCheck} to {@code srcOfTruth}.
   * @param contentToCheck the content that needs to be checked against the {@code srcOfTruth}.
   * @param srcOfTruth the original content.
   */
  private void compareContent(ByteBuffer contentToCheck, List<ByteBuffer> srcOfTruth) {
    ByteBuffer truth;
    int counter = 0;
    truth = srcOfTruth.get(counter++);
    while (contentToCheck.hasRemaining()) {
      if (!truth.hasRemaining()) {
        truth = srcOfTruth.get(counter++);
      }
      assertEquals("Byte in actual content differs from original content", truth.get(), contentToCheck.get());
    }
  }

  // requestHandleWithGoodInputTest() helpers

  /**
   * Does a test to see that request handling with good input succeeds when channel is not keep alive.
   * @param httpMethod the {@link HttpMethod} for the request.
   * @param restMethod the equivalent {@link RestMethod} for {@code httpMethod}. Used to check for correctness of
   *                   response.
   * @throws IOException
   */
  private void doRequestHandleWithoutKeepAlive(HttpMethod httpMethod, RestMethod restMethod)
      throws IOException {
    EmbeddedChannel channel = createChannel();
    sendRequestCheckResponse(channel, httpMethod, restMethod, false);
    assertFalse("Channel not closed", channel.isOpen());
  }

  /**
   * Does a test to see that request handling with good input succeeds when channel is keep alive.
   * @param channel the {@link EmbeddedChannel} to use.
   * @param httpMethod the {@link HttpMethod} for the request.
   * @param restMethod the equivalent {@link RestMethod} for {@code httpMethod}. Used to check for correctness of
   *                   response.
   * @throws IOException
   */
  private void doRequestHandleWithKeepAlive(EmbeddedChannel channel, HttpMethod httpMethod, RestMethod restMethod)
      throws IOException {
    for (int i = 0; i < 5; i++) {
      sendRequestCheckResponse(channel, httpMethod, restMethod, true);
      assertTrue("Channel is closed", channel.isOpen());
    }
  }

  // multipartPostTest() helpers.

  /**
   * Creates a {@link HttpPostRequestEncoder} that encodes the given {@code request} and {@code blobContent}.
   * @param request the {@link HttpRequest} containing headers and other metadata about the request.
   * @param blobContent the {@link ByteBuffer} that represents the content of the blob.
   * @return a {@link HttpPostRequestEncoder} that can encode the {@code request} and {@code blobContent}.
   * @throws HttpPostRequestEncoder.ErrorDataEncoderException
   * @throws IOException
   */
  private HttpPostRequestEncoder createEncoder(HttpRequest request, ByteBuffer blobContent)
      throws HttpPostRequestEncoder.ErrorDataEncoderException, IOException {
    HttpDataFactory httpDataFactory = new DefaultHttpDataFactory(false);
    HttpPostRequestEncoder encoder = new HttpPostRequestEncoder(httpDataFactory, request, true);
    FileUpload fileUpload = new MemoryFileUpload(RestUtils.MultipartPost.BLOB_PART, RestUtils.MultipartPost.BLOB_PART,
        "application/octet-stream", "", Charset.forName("UTF-8"), blobContent.remaining());
    fileUpload.setContent(Unpooled.wrappedBuffer(blobContent));
    encoder.addBodyHttpData(fileUpload);
    return encoder;
  }

  // requestHandlerExceptionTest() helpers.

  /**
   * Does a test where the request handler inside {@link NettyMessageProcessor} fails. Checks for the right error code
   * in the response.
   * @param httpMethod the {@link HttpMethod} to use for the request.
   * @param expectedStatus the excepted {@link HttpResponseStatus} in the response.
   */
  private void doRequestHandlerExceptionTest(HttpMethod httpMethod, HttpResponseStatus expectedStatus) {
    EmbeddedChannel channel = createChannel();
    channel.writeInbound(RestTestUtils.createRequest(httpMethod, "/", null));
    channel.writeInbound(new DefaultLastHttpContent());
    // first outbound has to be response.
    HttpResponse response = (HttpResponse) channel.readOutbound();
    assertEquals("Unexpected response status", expectedStatus, response.getStatus());
  }

  /**
   * A notification system that helps track events in the {@link InMemoryRouter}. Not thread safe and has to be
   * {@link #reset()} before every operation for which it is used.
   */
  private class HelperNotificationSystem implements NotificationSystem {
    /**
     * The blob id of the blob that the last operation was on.
     */
    protected volatile String blobIdOperatedOn = null;
    /**
     * Latch for awaiting the completion of an operation.
     */
    protected volatile CountDownLatch operationCompleted = new CountDownLatch(1);

    @Override
    public void onBlobCreated(String blobId, BlobProperties blobProperties, byte[] userMetadata) {
      blobIdOperatedOn = blobId;
      operationCompleted.countDown();
    }

    @Override
    public void onBlobDeleted(String blobId) {
      throw new IllegalStateException("Not implemented");
    }

    @Override
    public void onBlobReplicaCreated(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
      throw new IllegalStateException("Not implemented");
    }

    @Override
    public void onBlobReplicaDeleted(String sourceHost, int port, String blobId, BlobReplicaSourceType sourceType) {
      throw new IllegalStateException("Not implemented");
    }

    @Override
    public void close() {
      // no op.
    }

    /**
     * Resets the state and prepares this instance for another operation.
     */
    protected void reset() {
      blobIdOperatedOn = null;
      operationCompleted = new CountDownLatch(1);
    }
  }
}
