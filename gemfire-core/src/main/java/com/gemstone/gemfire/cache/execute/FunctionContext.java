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
package com.gemstone.gemfire.cache.execute;

/**
 * Defines the execution context of a {@link Function}. It is required
 * by the {@link Function#execute(FunctionContext)} to execute a {@link Function}
 * on a particular member.
 * <p>
 * A context can be data dependent or data independent.
 * For data dependent functions refer to {@link RegionFunctionContext}
 * </p>
 * <p>This interface is implemented by GemFire. Instances of it will be passed
 * in to {@link Function#execute(FunctionContext)}.
 * 
 * @author Yogesh Mahajan
 * @author Mitch Thomas
 *
 * @since 6.0
 *
 * @see RegionFunctionContext
 *
 */
public interface FunctionContext {
  /**
   * Returns the arguments provided to this function execution. These are the
   * arguments specified by the caller using
   * {@link Execution#withArgs(Object)}
   * 
   * @return the arguments or null if there are no arguments
   * @since 6.0
   */
  public Object getArguments();

  /**
   * Returns the identifier of the function.
   *  
   * @return a unique identifier
   * @see Function#getId()
   * @since 6.0
   */
  public String getFunctionId();
  
  /**
   * Returns the ResultSender which is used to add the ability for an execute
   * method to send a single result back, or break its result into multiple
   * pieces and send each piece back to the calling thread's ResultCollector.
   * 
   * @return ResultSender
   * @since 6.0
   */
  
  public <T> ResultSender<T> getResultSender();
  
  /**
   * Returns a boolean to identify whether this is a re-execute. Returns true if
   * it is a re-execute else returns false
   * 
   * @return a boolean (true) to identify whether it is a re-execute (else
   *         false)
   * 
   * @since 6.5
   * @see Function#isHA()
   */
  public boolean isPossibleDuplicate();
}
