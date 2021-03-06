/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.jdbc.authentication.JNDIAuthenticationSchemeBase

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package com.pivotal.gemfirexd.internal.impl.jdbc.authentication;




import com.pivotal.gemfirexd.auth.callback.UserAuthenticator;
import com.pivotal.gemfirexd.internal.iapi.error.ExceptionSeverity;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.jdbc.AuthenticationService;
import com.pivotal.gemfirexd.internal.iapi.reference.MessageId;
import com.pivotal.gemfirexd.internal.iapi.reference.SQLState;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.i18n.MessageService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.store.access.AccessFactory;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;

import java.util.Properties;
import java.util.Enumeration;
import java.sql.SQLException;

/**
 * This is the base JNDI authentication scheme class.
 *
 * The generic environment JNDI properties for the selected JNDI
 * scheme are retrieved here so that the user can set JNDI properties
 * at the database or system level.
 *
 * @see com.pivotal.gemfirexd.auth.callback.UserAuthenticator 
 *
 */

public abstract class JNDIAuthenticationSchemeBase implements UserAuthenticator
{
	protected  final JNDIAuthenticationService authenticationService;
	protected String providerURL;

	private AccessFactory store;
	protected Properties initDirContextEnv;

	//
	// Constructor
	//
	// We get passed some Users properties if the authentication service
	// could not set them as part of System properties.
	//
	public JNDIAuthenticationSchemeBase(JNDIAuthenticationService as, Properties dbProperties) {

			this.authenticationService = as;

			//
			// Let's initialize the Directory Context environment based on
			// generic JNDI properties. Each JNDI scheme can then add its
			// specific scheme properties on top of it.
			//
			setInitDirContextEnv(dbProperties);

			// Specify the ones for this scheme if not already specified
			this.setJNDIProviderProperties();
	}


	/**
	 * To be OVERRIDEN by subclasses. This basically tests and sets
	 * default/expected JNDI properties for the JNDI provider scheme.
	 *
	 **/
	abstract protected void setJNDIProviderProperties();

	/**
	 * Construct the initial JNDI directory context environment Properties
	 * object. We retrieve JNDI environment properties that the user may
	 * have set at the database level.
	 *
	 **/
	private void setInitDirContextEnv(Properties dbProps) {

		//
		// We retrieve JNDI properties set at the database level	
		// if any. If dbProps == null, there are obviously no database
		// properties to retrieve.
		//
		initDirContextEnv = new Properties();
                
		if(dbProps != null) {
			for (Enumeration keys = dbProps.propertyNames(); keys.hasMoreElements(); ) {

				String key = (String) keys.nextElement();

				if (key.startsWith("java.naming.")) {
					initDirContextEnv.put(key, dbProps.getProperty(key));
				}
			}
		}
	}
	
	protected static final SQLException getLoginSQLException(Exception e) {

		String text = MessageService.getTextMessage(SQLState.LOGIN_FAILED, e);

		SQLException sqle = new SQLException(
							text, SQLState.LOGIN_FAILED, ExceptionSeverity.SESSION_SEVERITY);

		return sqle;
	}

}
