/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.gemstone.gemfire.internal.cache.tier.sockets.command;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.LowMemoryException;
import com.gemstone.gemfire.cache.execute.Function;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.cache.execute.FunctionException;
import com.gemstone.gemfire.cache.execute.FunctionService;
import com.gemstone.gemfire.cache.operations.ExecuteFunctionOperationContext;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DM;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.TXManagerImpl;
import com.gemstone.gemfire.internal.cache.TXStateInterface;
import com.gemstone.gemfire.internal.cache.control.HeapMemoryMonitor;
import com.gemstone.gemfire.internal.cache.control.InternalResourceManager;
import com.gemstone.gemfire.internal.cache.control.MemoryThresholds;
import com.gemstone.gemfire.internal.cache.execute.AbstractExecution;
import com.gemstone.gemfire.internal.cache.execute.FunctionContextImpl;
import com.gemstone.gemfire.internal.cache.execute.FunctionStats;
import com.gemstone.gemfire.internal.cache.execute.MemberMappedArgument;
import com.gemstone.gemfire.internal.cache.execute.ServerToClientFunctionResultSender;
import com.gemstone.gemfire.internal.cache.execute.ServerToClientFunctionResultSender65;
import com.gemstone.gemfire.internal.cache.tier.Command;
import com.gemstone.gemfire.internal.cache.tier.MessageType;
import com.gemstone.gemfire.internal.cache.tier.sockets.BaseCommand;
import com.gemstone.gemfire.internal.cache.tier.sockets.ChunkedMessage;
import com.gemstone.gemfire.internal.cache.tier.sockets.HandShake;
import com.gemstone.gemfire.internal.cache.tier.sockets.Message;
import com.gemstone.gemfire.internal.cache.tier.sockets.Part;
import com.gemstone.gemfire.internal.cache.tier.sockets.ServerConnection;
import com.gemstone.gemfire.internal.concurrent.AI;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;
import com.gemstone.gemfire.internal.security.AuthorizeRequest;

/**
 * @author kbachhav
 * @since 6.6
 */
public class ExecuteFunction66 extends BaseCommand {

  private final static ExecuteFunction66 singleton = new ExecuteFunction66();

  protected static volatile boolean ASYNC_TX_WARNING_ISSUED = false;

  static final ExecutorService execService = Executors
      .newCachedThreadPool(new ThreadFactory() {
        AI threadNum = CFactory.createAI();

        public Thread newThread(final Runnable r) {
          Thread result = new Thread(r, "Function Execution Thread-"
              + threadNum.incrementAndGet());
          result.setDaemon(true);
          return result;
        }
      });
  
  public static Command getCommand() {
    return singleton;
  }

  protected ExecuteFunction66() {
  }

  @Override
  public void cmdExecute(Message msg, ServerConnection servConn, long start)
      throws IOException {
    Object function = null;
    Object args = null;
    MemberMappedArgument memberMappedArg = null;
    String[] groups = null;
    byte hasResult = 0;
    byte functionState = 0;
    boolean isReexecute = false;
    boolean allMembers = false;
    boolean ignoreFailedMembers = false;
    try {
      functionState = msg.getPart(0).getSerializedForm()[0];

      if (functionState == AbstractExecution.HA_HASRESULT_NO_OPTIMIZEFORWRITE_REEXECUTE) {
        functionState = AbstractExecution.HA_HASRESULT_NO_OPTIMIZEFORWRITE;
        isReexecute = true;
      }
      else if (functionState == AbstractExecution.HA_HASRESULT_OPTIMIZEFORWRITE_REEXECUTE) {
        functionState = AbstractExecution.HA_HASRESULT_OPTIMIZEFORWRITE;
        isReexecute = true;
      }

      if (functionState != 1) {
        hasResult = (byte)((functionState & 2) - 1);
      }
      else {
        hasResult = functionState;
      }
      if (hasResult == 1) {
        servConn.setAsTrue(REQUIRES_RESPONSE);
        servConn.setAsTrue(REQUIRES_CHUNKED_RESPONSE);
      }
      function = msg.getPart(1).getStringOrObject();
      args = msg.getPart(2).getObject();

      Part part = msg.getPart(3);
      if (part != null) {
        memberMappedArg = (MemberMappedArgument)part.getObject();
      }

      groups = getGroups(msg);
      allMembers = getAllMembers(msg);
      ignoreFailedMembers = getIgnoreFailedMembers(msg);
    }
    catch (ClassNotFoundException exception) {
      if (logger.warningEnabled()) {
        logger
            .warning(
                LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                function, exception);
      }
      if (hasResult == 1) {
        writeChunkedException(msg, exception, false, servConn);
      }
      else {
        writeException(msg, exception, false, servConn);
      }
      servConn.setAsTrue(RESPONDED);
      return;
    }
    if (function == null) {
      final String message = LocalizedStrings.ExecuteFunction_THE_INPUT_FUNCTION_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
          .toLocalizedString();
      if (logger.warningEnabled())
        logger.warning(LocalizedStrings.TWO_ARG_COLON, new Object[] {
            servConn.getName(), message });
      sendError(hasResult, msg, message, servConn);
      return;
    }
    else {
      // Execute function on the cache
      try {
        Function functionObject = null;
        if (function instanceof String) {
          functionObject = FunctionService.getFunction((String)function);
          if (functionObject == null) {
            final String message = LocalizedStrings.ExecuteFunction_FUNCTION_NAMED_0_IS_NOT_REGISTERED
                .toLocalizedString(function);
            if (logger.warningEnabled()) {
              logger.warning(LocalizedStrings.TWO_ARG_COLON, new Object[] {
                  servConn.getName(), message });
            }
            sendError(hasResult, msg, message, servConn);
            return;
          }
          else {
            byte functionStateOnServerSide = AbstractExecution
                .getFunctionState(functionObject.isHA(), functionObject
                    .hasResult(), functionObject.optimizeForWrite());
            if (logger.fineEnabled()) {
              logger.fine("Function State on server side : "
                  + functionStateOnServerSide + " on client :" + functionState);
            }
            if (functionStateOnServerSide != functionState) {
              String message = LocalizedStrings.FunctionService_FUNCTION_ATTRIBUTE_MISMATCH_CLIENT_SERVER
                  .toLocalizedString(function);
              if (logger.warningEnabled()) {
                logger.warning(LocalizedStrings.ONE_ARG, servConn.getName()
                    + ": " + message);
              }
              sendError(hasResult, msg, message, servConn);
              return;
            }
          }
        }
        else {
          functionObject = (Function)function;
        }
        FunctionStats stats = FunctionStats.getFunctionStats(functionObject
            .getId(), null);
        // check if the caller is authorized to do this operation on server
        AuthorizeRequest authzRequest = servConn.getAuthzRequest();
        ExecuteFunctionOperationContext executeContext = null;
        if (authzRequest != null) {
          executeContext = authzRequest.executeFunctionAuthorize(functionObject
              .getId(), null, null, functionObject.optimizeForWrite());
        }
        ChunkedMessage m = servConn.getFunctionResponseMessage();
        m.setTransactionId(msg.getTransactionId());
        ServerToClientFunctionResultSender resultSender = new ServerToClientFunctionResultSender65(m,
            MessageType.EXECUTE_FUNCTION_RESULT, servConn, functionObject,
            executeContext);

        InternalDistributedMember localVM = InternalDistributedSystem
            .getAnyInstance().getDistributedMember();
        FunctionContext context = null;

        if (memberMappedArg != null) {
          context = new FunctionContextImpl(functionObject.getId(),
              memberMappedArg.getArgumentsForMember(localVM.getId()),
              resultSender, isReexecute);
        }
        else {
          context = new FunctionContextImpl(functionObject.getId(), args,
              resultSender, isReexecute);
        }
        HandShake handShake = (HandShake)servConn.getHandshake();
        int earlierClientReadTimeout = handShake.getClientReadTimeout();
        handShake.setClientReadTimeout(0);
        try {
          if (logger.fineEnabled()) {
            logger.fine("Executing Function on Server: " + servConn.toString()
                + "with context :" + context.toString());
          }
          GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
          HeapMemoryMonitor hmm = ((InternalResourceManager) cache.getResourceManager()).getHeapMonitor();
          if (functionObject.optimizeForWrite() && cache != null
              && hmm.getState().isCritical()
              && !MemoryThresholds.isLowMemoryExceptionDisabled()) {
            Set<DistributedMember> sm = Collections
                .singleton((DistributedMember)cache.getMyId());
            Exception e = new LowMemoryException(
                LocalizedStrings.ResourceManager_LOW_MEMORY_FOR_0_FUNCEXEC_MEMBERS_1
                    .toLocalizedString(new Object[] { functionObject.getId(),
                        sm }), sm);

            sendException(hasResult, msg, e.getMessage(), servConn, e);
            return;
          }
          /**
           * if cache is null, then either cache has not yet been created on
           * this node or it is a shutdown scenario.
           */
          DM dm = null;
          if (cache != null) {
            dm = cache.getDistributionManager();
          }
          if (groups != null && groups.length > 0) {
            executeFunctionOnGroups(function, args, groups, allMembers,
                functionObject, resultSender, ignoreFailedMembers);
          } else {
            executeFunctionaLocally(functionObject, context,
                (ServerToClientFunctionResultSender65)resultSender, dm, stats);
          }

          if (!functionObject.hasResult()) {
            writeReply(msg, servConn);
          }
        }
        catch (FunctionException functionException) {
          stats.endFunctionExecutionWithException(functionObject.hasResult());
          throw functionException;
        }
        catch (Exception exception) {
          stats.endFunctionExecutionWithException(functionObject.hasResult());
          throw new FunctionException(exception);
        }
        finally {
          handShake.setClientReadTimeout(earlierClientReadTimeout);
        }
      }
      catch (IOException ioException) {
        if (logger.warningEnabled()) {
          logger
              .warning(
                  LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                  function, ioException);
        }
        String message = LocalizedStrings.ExecuteFunction_SERVER_COULD_NOT_SEND_THE_REPLY
            .toLocalizedString();
        sendException(hasResult, msg, message, servConn, ioException);
      }
      catch (Exception e) {
        if (logger.warningEnabled()) {
          logger
              .warning(
                  LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                  function, e);
        }
        final String message = e.getMessage();
        sendException(hasResult, msg, message, servConn, e);
      }
    }
  }

  protected boolean getIgnoreFailedMembers(Message msg) {
    return false;
  }

  protected boolean getAllMembers(Message msg) {
    return false;
  }

  protected void executeFunctionOnGroups(Object function, Object args,
      String[] groups, boolean allMembers, Function functionObject,
      ServerToClientFunctionResultSender resultSender, boolean ignoreFailedMembers) {
    throw new InternalGemFireError();
  }

  protected String[] getGroups(Message msg) throws IOException, ClassNotFoundException {
    return null;
  }

  private void executeFunctionaLocally(final Function fn,
      final FunctionContext cx,
      final ServerToClientFunctionResultSender65 sender, DM dm,
      final FunctionStats stats) throws IOException {
    long startExecution = stats.startTime();
    stats.startFunctionExecution(fn.hasResult());

    if (fn.hasResult()) {
      fn.execute(cx);
      if (!(sender)
          .isLastResultReceived() && fn.hasResult()) {
        throw new FunctionException(
            LocalizedStrings.ExecuteFunction_THE_FUNCTION_0_DID_NOT_SENT_LAST_RESULT
                .toString(fn.getId()));
      }
    } else {
      /**
       * if dm is null it mean cache is also null. Transactional function
       * without cache cannot be executed.
       */
      final TXStateInterface txState = TXManagerImpl.getCurrentTXState();
      Runnable functionExecution = new Runnable() {
        public void run() {
          GemFireCacheImpl cache = null;
          TXManagerImpl.TXContext context = null;
          try {
            if (txState != null) {
              cache = GemFireCacheImpl.getExisting("executing function");
              context = cache.getTxManager().masqueradeAs(txState);
              if (cache.getLoggerI18n().warningEnabled()
                  && !ASYNC_TX_WARNING_ISSUED) {
                ASYNC_TX_WARNING_ISSUED = true;
                cache
                    .getLoggerI18n()
                    .warning(
                        LocalizedStrings.ExecuteFunction66_TRANSACTIONAL_FUNCTION_WITHOUT_RESULT);
              }
            }
            fn.execute(cx);
          } catch (FunctionException functionException) {
            stats.endFunctionExecutionWithException(fn.hasResult());
            if (logger.warningEnabled()) {
              logger
                  .warning(
                      LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                      fn, functionException);
            }
          } catch (Exception exception) {
            stats.endFunctionExecutionWithException(fn.hasResult());
            if (logger.warningEnabled()) {
              logger
                  .warning(
                      LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                      fn, exception);
            }
          } finally {
            if (txState != null && cache != null) {
              cache.getTxManager().unmasquerade(context, true);
            }
          }
        }
      };

      if (dm == null) {
        /**
         * Executing the function in its own thread pool as FunctionExecution
         * Thread pool of DisributionManager is not yet available.
         */
        execService.execute(functionExecution);
      } else {
        final DistributionManager newDM = (DistributionManager)dm;
        newDM.getFunctionExcecutor().execute(functionExecution);
      }
    }
    stats.endFunctionExecution(startExecution, fn.hasResult());
  }

  private void sendException(byte hasResult, Message msg, String message,
      ServerConnection servConn, Throwable e) throws IOException {
    if (hasResult == 1) {
      writeFunctionResponseException(msg, MessageType.EXCEPTION, message,
          servConn, e);
    }
    else {
      writeException(msg, e, false, servConn);
    }
    servConn.setAsTrue(RESPONDED);
  }

  private void sendError(byte hasResult, Message msg, String message,
      ServerConnection servConn) throws IOException {
    if (hasResult == 1) {
      writeFunctionResponseError(msg, MessageType.EXECUTE_FUNCTION_ERROR,
          message, servConn);
    }
    else {
      writeErrorResponse(msg, MessageType.EXECUTE_FUNCTION_ERROR, message,
          servConn);
    }
    servConn.setAsTrue(RESPONDED);
  }

}
