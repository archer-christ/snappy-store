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
package com.gemstone.gemfire.internal.tools.gfsh.app.aggregator;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.gemstone.gemfire.DataSerializer;
import com.gemstone.gemfire.internal.tools.gfsh.aggregator.AggregateFunction;
import com.gemstone.gemfire.internal.tools.gfsh.command.AbstractCommandTask;
import com.gemstone.gemfire.internal.tools.gfsh.command.CommandResults;

/**
 * AggregateFunctionTask is used by Aggregator.
 * 
 * @author dpark
 */
public class AggregateFunctionTask extends AbstractCommandTask 
{
	private static final long serialVersionUID = 1L;
	
	private String regionFullPath;
	private AggregateFunction function;
	
	// Default constructor required for serialization
	public AggregateFunctionTask()
	{
	}
	
	public AggregateFunctionTask(AggregateFunction function, String regionFullPath)
	{
		this.function = function;
		this.regionFullPath = regionFullPath;
	}
	
	public CommandResults runTask(Object userData) 
	{
		CommandResults results = new CommandResults();
		try {
			AggregatorPeer aggregator = new AggregatorPeer(regionFullPath);
			results.setDataObject(aggregator.aggregate(function));
		} catch (Exception ex) {
			results.setCode(CommandResults.CODE_ERROR);
			results.setException(ex);
		}
		return results;
	}

	public void fromData(DataInput input) throws IOException, ClassNotFoundException 
	{
		super.fromData(input);
		regionFullPath = DataSerializer.readString(input);
		function = (AggregateFunction)DataSerializer.readObject(input);
	}

	public void toData(DataOutput output) throws IOException 
	{
		super.toData(output);
		DataSerializer.writeString(regionFullPath, output);
		DataSerializer.writeObject(function, output);
	}

}
