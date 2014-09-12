/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/

package com.ibm.streamsx.messaging.mqtt;

public class ConnectionSpecification  {
	private String serverUri;
	private String trustStore;
	private String trustStorePassword;
	private String keyStore;
	private String keyStorePassword;
	
	public String getServerUri() {
		return serverUri;
	}
	public ConnectionSpecification setServerUri(String serverUri) {
		this.serverUri = serverUri;
		return this;
	}
	public String getTrustStore() {
		return trustStore;
	}
	public ConnectionSpecification setTrustStore(String trustStore) {
		this.trustStore = trustStore;
		return this;
	}
	public String getKeyStore() {
		return keyStore;
	}
	public ConnectionSpecification setKeyStore(String keyStore) {
		this.keyStore = keyStore;
		return this;
	}
	public String getKeyStorePassword() {
		return keyStorePassword;
	}
	public ConnectionSpecification setKeyStorePassword(String keyStorePassword) {
		this.keyStorePassword = keyStorePassword;
		return this;
	}

	public ConnectionSpecification setTrustStorePassword(String trustStorePassword) {
		this.trustStorePassword = trustStorePassword;
		return this;
	}
	
	public String getTrustStorePassword() {
		return trustStorePassword;
	}

}