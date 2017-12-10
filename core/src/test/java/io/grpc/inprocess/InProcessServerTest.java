/*
 * Copyright 2016, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.inprocess;

import com.google.common.truth.Truth;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerStreamTracer;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.health.v1.HealthGrpc.HealthImplBase;
import io.grpc.internal.FakeClock;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.ObjectPool;
import io.grpc.internal.ServerListener;
import io.grpc.internal.ServerTransport;
import io.grpc.internal.ServerTransportListener;
import io.grpc.stub.StreamObserver;
import java.util.Collections;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class InProcessServerTest {
  private Metadata.Key<String> KEY = Metadata.Key.of("ttt", Metadata.ASCII_STRING_MARSHALLER);

  class LoggingInterceptor implements ServerInterceptor {
    @Override
    public <ReqT, RespT> Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {
      System.out.println("LoggingInterceptor#interceptCall = " + headers);
      return next.startCall(call, headers);
    }
  }

  class CustumHeaderInterceptor implements ClientInterceptor {
    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions, Channel next
    ) {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          headers.put(KEY, "foo");
          super.start(new SimpleForwardingClientCallListener<RespT>(responseListener) {
            @Override
            public void onHeaders(Metadata headers) {
              super.onHeaders(headers);
            }
          }, headers);
        }
      };
    }
  }

  @Test
  public void foo() throws Exception {
    Server s = InProcessServerBuilder
        .forName("a")
        .addService(new HealthImplBase() {
          @Override
          public void check(HealthCheckRequest request,
              StreamObserver<HealthCheckResponse> responseObserver) {
            responseObserver.onNext(HealthCheckResponse.newBuilder().build());
            responseObserver.onCompleted();
          }
        }).intercept(new LoggingInterceptor())
        .build();
    s.start();
    Channel c = InProcessChannelBuilder
        .forName("a")
        .userAgent("grpc-a")
        .build();
    Channel cc = ClientInterceptors.intercept(c, new CustumHeaderInterceptor());
    HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(cc);
    stub.check(HealthCheckRequest.newBuilder().build());
  }

  @Test
  public void getPort_notStarted() throws Exception {
    InProcessServer s =
        new InProcessServer("name", GrpcUtil.TIMER_SERVICE,
            Collections.<ServerStreamTracer.Factory>emptyList());

    Truth.assertThat(s.getPort()).isEqualTo(-1);
  }

  @Test
  public void serverHoldsRefToScheduler() throws Exception {
    final ScheduledExecutorService ses = new FakeClock().getScheduledExecutorService();
    class RefCountingObjectPool implements ObjectPool<ScheduledExecutorService> {
      private int count;

      @Override
      public ScheduledExecutorService getObject() {
        count++;
        return ses;
      }

      @Override
      public ScheduledExecutorService returnObject(Object returned) {
        count--;
        return null;
      }
    }

    RefCountingObjectPool pool = new RefCountingObjectPool();
    InProcessServer s =
        new InProcessServer("name", pool, Collections.<ServerStreamTracer.Factory>emptyList());
    Truth.assertThat(pool.count).isEqualTo(0);
    s.start(new ServerListener() {
      @Override public ServerTransportListener transportCreated(ServerTransport transport) {
        throw new UnsupportedOperationException();
      }

      @Override public void serverShutdown() {}
    });
    Truth.assertThat(pool.count).isEqualTo(1);
    s.shutdown();
    Truth.assertThat(pool.count).isEqualTo(0);
  }
}

