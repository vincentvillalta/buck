/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.remoteexecution.grpc;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.ExecuteRequest;
import build.bazel.remote.execution.v2.ExecuteResponse;
import build.bazel.remote.execution.v2.ExecutedActionMetadata;
import build.bazel.remote.execution.v2.ExecutionGrpc.ExecutionStub;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.remoteexecution.RemoteExecutionServiceClient;
import com.facebook.buck.remoteexecution.event.RemoteExecutionActionEvent;
import com.facebook.buck.remoteexecution.grpc.GrpcHeaderHandler.StubAndResponseMetadata;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcDigest;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputDirectory;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol.GrpcOutputFile;
import com.facebook.buck.remoteexecution.interfaces.MetadataProvider;
import com.facebook.buck.remoteexecution.interfaces.Protocol;
import com.facebook.buck.remoteexecution.interfaces.Protocol.Digest;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputDirectory;
import com.facebook.buck.remoteexecution.interfaces.Protocol.OutputFile;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.bytestream.ByteStreamGrpc.ByteStreamStub;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.longrunning.Operation;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Implementation of the GRPC client for the Remote Execution service. */
public class GrpcRemoteExecutionServiceClient implements RemoteExecutionServiceClient {
  private static final Logger LOG = Logger.get(GrpcRemoteExecutionServiceClient.class);

  private final ExecutionStub executionStub;
  private final ByteStreamStub byteStreamStub;
  private final String instanceName;
  private final Protocol protocol;

  public GrpcRemoteExecutionServiceClient(
      ExecutionStub executionStub,
      ByteStreamStub byteStreamStub,
      String instanceName,
      Protocol protocol) {
    this.executionStub = executionStub;
    this.byteStreamStub = byteStreamStub;
    this.instanceName = instanceName;
    this.protocol = protocol;
  }

  @Override
  public ListenableFuture<ExecutionResult> execute(
      Protocol.Digest actionDigest, String ruleName, MetadataProvider metadataProvider)
      throws IOException, InterruptedException {
    SettableFuture<Operation> future = SettableFuture.create();

    StubAndResponseMetadata<ExecutionStub> stubAndMetadata =
        GrpcHeaderHandler.wrapStubToSendAndReceiveMetadata(
            executionStub,
            metadataProvider.getForAction(
                RemoteExecutionActionEvent.actionDigestToString(actionDigest), ruleName));
    stubAndMetadata
        .getStub()
        .execute(
            ExecuteRequest.newBuilder()
                .setInstanceName(instanceName)
                .setActionDigest(GrpcProtocol.get(actionDigest))
                .setSkipCacheLookup(false)
                .build(),
            new StreamObserver<Operation>() {
              @Nullable Operation op = null;

              @Override
              public void onNext(Operation value) {
                op = value;
              }

              @Override
              public void onError(Throwable t) {
                String msg =
                    String.format(
                        "Failed execution request with metadata=[%s] and exception=[%s].",
                        stubAndMetadata.getMetadata(), t.toString());
                LOG.error(msg);
                future.setException(new IOException(msg, t));
              }

              @Override
              public void onCompleted() {
                future.set(op);
              }
            });

    return Futures.transform(
        future,
        operation -> {
          Objects.requireNonNull(operation);
          if (operation.hasError()) {
            throw new RuntimeException(
                String.format(
                    "Execution failed due to an infra error with Status=[%s] Metadata=[%s].",
                    operation.getError().toString(), stubAndMetadata.getMetadata()));
          }

          if (!operation.hasResponse()) {
            throw new RuntimeException(
                String.format(
                    "Invalid operation response: missing ExecutionResponse object. "
                        + "Response=[%s] Metadata=[%s].",
                    operation.toString(), stubAndMetadata.getMetadata()));
          }

          try {
            return getExecutionResult(
                operation.getResponse().unpack(ExecuteResponse.class).getResult());
          } catch (InvalidProtocolBufferException e) {
            throw new BuckUncheckedExecutionException(
                e,
                "Exception getting execution result with Metadata=[%s].",
                stubAndMetadata.getMetadata());
          }
        },
        MoreExecutors.directExecutor());
  }

  private ExecutionResult getExecutionResult(ActionResult actionResult) {
    if (actionResult.getExitCode() != 0) {
      LOG.debug(
          "Got failed action from worker %s", actionResult.getExecutionMetadata().getWorker());
    }
    return new ExecutionResult() {
      @Override
      public List<OutputDirectory> getOutputDirectories() {
        return actionResult
            .getOutputDirectoriesList()
            .stream()
            .map(GrpcOutputDirectory::new)
            .collect(Collectors.toList());
      }

      @Override
      public List<OutputFile> getOutputFiles() {
        return actionResult
            .getOutputFilesList()
            .stream()
            .map(GrpcOutputFile::new)
            .collect(Collectors.toList());
      }

      @Override
      public int getExitCode() {
        return actionResult.getExitCode();
      }

      @Override
      public Optional<String> getStdout() {
        ByteString stdoutRaw = actionResult.getStdoutRaw();
        if (stdoutRaw == null
            || (stdoutRaw.isEmpty() && actionResult.getStdoutDigest().getSizeBytes() > 0)) {
          LOG.debug("Got stdout digest.");
          return Optional.of(fetch(new GrpcDigest(actionResult.getStdoutDigest())));
        } else {
          LOG.debug("Got raw stdout: " + stdoutRaw.toStringUtf8());
          return Optional.of(stdoutRaw.toStringUtf8());
        }
      }

      @Override
      public Optional<String> getStderr() {
        ByteString stderrRaw = actionResult.getStderrRaw();
        if (stderrRaw == null
            || (stderrRaw.isEmpty() && actionResult.getStderrDigest().getSizeBytes() > 0)) {
          LOG.debug("Got stderr digest.");
          return Optional.of(fetch(new GrpcDigest(actionResult.getStderrDigest())));
        } else {
          LOG.debug("Got raw stderr: " + stderrRaw.toStringUtf8());
          return Optional.of(stderrRaw.toStringUtf8());
        }
      }

      @Override
      public Digest getActionResultDigest() {
        return protocol.computeDigest(actionResult.toByteArray());
      }

      @Override
      public ExecutedActionMetadata getActionMetadata() {
        return actionResult.getExecutionMetadata();
      }

      private String fetch(Protocol.Digest digest) {
        /** Payload received on a fetch request. */
        class Data {
          ByteString data = ByteString.EMPTY;

          public void concat(ByteString bytes) {
            data = data.concat(bytes);
          }
        }
        Data data = new Data();
        try {
          GrpcRemoteExecutionClients.readByteStream(
                  instanceName, digest, byteStreamStub, data::concat)
              .get();
        } catch (InterruptedException | ExecutionException e) {
          throw new RuntimeException(e);
        }
        return data.data.toStringUtf8();
      }
    };
  }
}
