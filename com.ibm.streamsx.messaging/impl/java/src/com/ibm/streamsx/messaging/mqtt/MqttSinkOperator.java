/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/

/* Generated by Streams Studio: 28 February, 2014 12:15:29 PM EST */
package com.ibm.streamsx.messaging.mqtt;


import java.io.InputStream;
import java.lang.Thread.State;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;

import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.types.Blob;

/**
 * Class for an operator that consumes tuples and does not produce an output stream. 
 * This pattern supports a number of input streams and no output streams. 
 * <P>
 * The following event methods from the Operator interface can be called:
 * </p> 
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>process() handles a tuple arriving on an input port 
 * <li>processPuncuation() handles a punctuation mark arriving on an input port 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(name="MQTTSink", namespace="com.ibm.streamsx.messaging.mqtt",
description=SPLDocConstants.MQTTSINK_OP_DESCRIPTION) 
@InputPorts({
		@InputPortSet(description = SPLDocConstants.MQTTSINK_INPUTPORT0, cardinality = 1, optional = false, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious),
		@InputPortSet(description = SPLDocConstants.MQTTSINK_INPUTPORT1, optional = true, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious) })
@OutputPorts({
		@OutputPortSet(description = SPLDocConstants.MQTTSINK_OUTPUT_PORT0, cardinality = 1, optional = true, windowPunctuationOutputMode = WindowPunctuationOutputMode.Free) })
@Libraries(value = {"opt/downloaded/*"} )
@Icons(location16="icons/MQTTSink_16.gif", location32="icons/MQTTSink_32.gif")
public class MqttSinkOperator extends AbstractMqttOperator {
	 
	static Logger TRACE = Logger.getLogger(MqttSinkOperator.class);
	
	// Parameters
	private String topic;
	private int qos = 0;
	private int reconnectionBound = IMqttConstants.DEFAULT_RECONNECTION_BOUND;		// default 5, 0 = no retry, -1 = infinite retry
	private long period = IMqttConstants.DEFAULT_RECONNECTION_PERIOD;
	private boolean retain = false;
	private String topicAttributeName;
	private String qosAttributeName;
	private MqttClientWrapper mqttWrapper;
	
	private ArrayBlockingQueue<Tuple> tupleQueue;
	private boolean shutdown;

	private Thread publishThread;
	
	private class PublishRunnable implements Runnable {

		@Override
		public void run() {
			while (!shutdown)
			{
				// publish tuple in the background thread
				// max 50 tuples in flight
				try {
					
					Tuple tuple = tupleQueue.take();	
					
					String pubTopic = topic;
					int msgQos = qos;
					
					if (topicAttributeName != null)
					{
						pubTopic = tuple.getString(topicAttributeName);
					}
					
					if (qosAttributeName != null)
					{
						msgQos = tuple.getInt(qosAttributeName);
					}
					
					// disconnect if we have received a control signal
					if (!mqttWrapper.getPendingBrokerUri().isEmpty())
					{
						mqttWrapper.disconnect();
					}
					
					// if connected, go straight to publishing
					if (mqttWrapper.isConnected())
					{
						// inline this block of code instead of method call
						// to avoid unnecessary method call overhead
						if (pubTopic != null && pubTopic.length() > 0
							&& msgQos >= 0 && msgQos < 3){
							Blob blockMsg = tuple.getBlob(0);
					        InputStream inputStream = blockMsg.getInputStream();
					        int length = (int) blockMsg.getLength();
					        byte[] byteArray = new byte[length];
					        inputStream.read(byteArray, 0, length);
					        mqttWrapper.publish(pubTopic, msgQos, byteArray, retain);
						}
						else
						{
							String errorMsg = Messages.getString("Error_MqttSinkOperator.0", pubTopic, msgQos); //$NON-NLS-1$
							TRACE.log(TraceLevel.ERROR, errorMsg); 
							submitToErrorPort(errorMsg);
						}
					}
					else
					{
						// if not connected, connect before publishing
						boolean connected = validateConnection();
						
						while (!connected && mqttWrapper.isUriChanged(mqttWrapper.getBrokerUri()))
						{
							connected = validateConnection();
						}
						
						if (!connected)
						{
							String errorMsg = Messages.getString("Error_MqttSinkOperator.1", getServerUri()); //$NON-NLS-1$
							submitToErrorPort(errorMsg);
							throw new RuntimeException(errorMsg); 
						}
						
						// inline this block of code instead of method call
						// to avoid unnecessary method call overhead
						if (pubTopic != null && pubTopic.length() > 0
								&& msgQos >= 0 && msgQos < 3){
							Blob blockMsg = tuple.getBlob(0);
					        InputStream inputStream = blockMsg.getInputStream();
					        int length = (int) blockMsg.getLength();
					        byte[] byteArray = new byte[length];
					        inputStream.read(byteArray, 0, length);
					        mqttWrapper.publish(pubTopic, msgQos, byteArray, retain);
						}
						else
						{
							String errorMsg = Messages.getString("Error_MqttSinkOperator.0", pubTopic, msgQos); //$NON-NLS-1$
							TRACE.log(TraceLevel.ERROR, errorMsg); //$NON-NLS-1$
							submitToErrorPort(errorMsg);
						}
					}
				} 
				catch (MqttClientConnectException e)
				{
					// we should exit if we get a connect exception
					if (e instanceof MqttClientConnectException)
					{
						throw new RuntimeException(e);
					}
				}				
				catch (Exception e) {
					// do not rethrow exception, log and keep going
					String errorMsg = Messages.getString("Error_MqttSinkOperator.2"); //$NON-NLS-1$
					TRACE.log(TraceLevel.ERROR, errorMsg, e); 
					submitToErrorPort(errorMsg);
				}
			}			
		}

		private boolean validateConnection() throws MqttClientConnectException{
			if (!mqttWrapper.isConnected())
			{
				try {
					if (!mqttWrapper.getPendingBrokerUri().isEmpty())
					{
						mqttWrapper.setBrokerUri(mqttWrapper.getPendingBrokerUri());
						
						// need to update parameter value too
						setServerUri(mqttWrapper.getPendingBrokerUri());
					}
					
					mqttWrapper.connect(getReconnectionBound(), getPeriod());
				} catch (URISyntaxException e) {
					String errorMsg = Messages.getString(Messages.getString("Error_MqttSinkOperator.22")); //$NON-NLS-1$
					TRACE.log(TraceLevel.ERROR, errorMsg, e);
					submitToErrorPort(errorMsg);	
					throw new RuntimeException(e);
				} catch (Exception e) {
					String errorMsg = Messages.getString(Messages.getString("Error_MqttSinkOperator.22")); //$NON-NLS-1$
					TRACE.log(TraceLevel.ERROR, errorMsg, e);
					submitToErrorPort(errorMsg);			
					
					if (e instanceof MqttClientConnectException)
					{
						throw (MqttClientConnectException)e;
					}
				} 
			}
			
			return mqttWrapper.isConnected();
		}		
	}
	
	@ContextCheck(compile=true, runtime=false)
	public static boolean compileCheckTopic(OperatorContextChecker checker)
	{
		OperatorContext context = checker.getOperatorContext();
		
		// check the topic and topicAttributeName parameters are mutually exclusive
		boolean check = checker.checkExcludedParameters("topic", "topicAttributeName") && //$NON-NLS-1$ //$NON-NLS-2$
				checker.checkExcludedParameters("topicAttributeName", "topic"); //$NON-NLS-1$ //$NON-NLS-2$
		
		// check that at least one of topic or topicAttributeName parameter is specified
		Set<String> parameterNames = context.getParameterNames();		
		boolean hasTopic = parameterNames.contains("topic") || parameterNames.contains("topicAttributeName"); //$NON-NLS-1$ //$NON-NLS-2$
		
		if (!hasTopic)
		{
			checker.setInvalidContext(Messages.getString("Error_MqttSinkOperator.7"), null); //$NON-NLS-1$
		}
		
		check = check & hasTopic;
		
		return check;
		
	}
	
	   @ContextCheck(compile=false)
	    public  static void runtimeChecks(OperatorContextChecker checker) {
	    	
	    	validateNumber(checker, "period", 0, Long.MAX_VALUE); //$NON-NLS-1$
	    	validateNumber(checker, "qos", 0, 2); //$NON-NLS-1$
	    	validateNumber(checker, "reconnectionBound", -1, Long.MAX_VALUE); //$NON-NLS-1$
	    	
	    	checkInputAttribute(checker, "qosAttributeName", MetaType.INT32); //$NON-NLS-1$
	    	checkInputAttribute(checker, "topicAttributeName", MetaType.RSTRING, MetaType.USTRING); //$NON-NLS-1$
	    }

	private static void checkInputAttribute(OperatorContextChecker checker, String parameterName, MetaType... validTypes) {
		if (checker.getOperatorContext().getParameterNames().contains(parameterName)) {
			
			List<String> parameterValues = checker.getOperatorContext().getParameterValues(parameterName);
			String attributeName = parameterValues.get(0);
			List<StreamingInput<Tuple>> inputPorts = checker.getOperatorContext().getStreamingInputs();
			if (inputPorts.size() > 0)
			{
				StreamingInput<Tuple> outputPort = inputPorts.get(0);
				StreamSchema streamSchema = outputPort.getStreamSchema();
				boolean check = checker.checkRequiredAttributes(streamSchema, attributeName);
				if (check)
					checker.checkAttributeType(streamSchema.getAttribute(attributeName), validTypes);
			}
		}
	}
	
	@ContextCheck(compile=true, runtime=false)
	public static void checkInputPortSchema(OperatorContextChecker checker) {
		List<StreamingInput<Tuple>> inputPorts = checker.getOperatorContext().getStreamingInputs();
		
		if (inputPorts.size() > 0)
		{
			StreamingInput<Tuple> dataPort = inputPorts.get(0);
			StreamSchema streamSchema = dataPort.getStreamSchema();
			Set<String> attributeNames = streamSchema.getAttributeNames();

			boolean blobFound = false;
			for (String attrName : attributeNames) {
				Attribute attr = streamSchema.getAttribute(attrName);
				
				if (attr.getType().getMetaType().equals(MetaType.BLOB))
				{
					blobFound = true;
					break;
				}				
			}
			if (!blobFound)
			{
				checker.setInvalidContext(Messages.getString("Error_MqttSinkOperator.5"), new Object[]{}); //$NON-NLS-1$
			}
		}
		//TODO:  check control input port
		
		
	}
	
	@ContextCheck(compile = true, runtime = false)
	public static void checkOutputPort(OperatorContextChecker checker) {
		validateSchemaForErrorOutputPort(checker, getErrorPortFromContext(checker.getOperatorContext()));
	}
	
    /**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
       tupleQueue = new ArrayBlockingQueue<Tuple>(50);
        
       mqttWrapper = new MqttClientWrapper();       
       initFromConnectionDocument();
       mqttWrapper.setBrokerUri(getServerUri());
       mqttWrapper.setReconnectionBound(getReconnectionBound());
       mqttWrapper.setPeriod(getPeriod());
       
       setupSslProperties(mqttWrapper);
       // do not connect here... connection is done on the publish thread when a message
       // is ready to be published
	} 
	
	/**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
    	// This method is commonly used by source operators. 
    	// Operators that process incoming tuples generally do not need this notification. 
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        publishThread = context.getThreadFactory().newThread(new PublishRunnable());
        publishThread.start();
        
    }

    /**
     * Process an incoming tuple that arrived on the specified port.
     * @param stream Port the tuple is arriving on.
     * @param tuple Object representing the incoming tuple.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple)  throws Exception {
    	
    	// if data port
    	if (stream.getPortNumber() == 0)
    	{
    		// put tuple to queue
    		tupleQueue.put(tuple);
    	}
    	
    	// else if control input port
    	else {
			TRACE.log(TraceLevel.DEBUG, "[Control Port:] Control Signal Received"); //$NON-NLS-1$

    		handleControlSignal(tuple);
    	}
    }

	private void handleControlSignal(Tuple tuple) {
		// handle control signal to switch server
		try {
			Object object = tuple.getObject(0);
			TRACE.log(TraceLevel.DEBUG, "[Control Port:] object: " + object + " " + object.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

			if (object instanceof Map)
			{									
				Map map = (Map)object;
				Set keySet = map.keySet();
				for (Iterator iterator = keySet.iterator(); iterator
						.hasNext();) {
					Object key = (Object) iterator.next();
					TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + key + " " + key.getClass()); //$NON-NLS-1$ //$NON-NLS-2$
					
					String keyStr = key.toString();
					
					// case insensitive checks
					if (keyStr.toLowerCase().equals(IMqttConstants.CONN_SERVERURI.toLowerCase()))
					{
						Object serverUri = map.get(key);				
						
						String serverUriStr = serverUri.toString();
						
						// only handle if server URI has changed
						if (!serverUriStr.toLowerCase().equals(getServerUri().toLowerCase()))
						{						
							TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + IMqttConstants.CONN_SERVERURI + ":" + serverUri); //$NON-NLS-1$ //$NON-NLS-2$
						
							// set pending broker URI to get wrapper out of retry loop
							mqttWrapper.setPendingBrokerUri(serverUriStr);
							
							// interrupt the publish thread in case it is sleeping
							if (publishThread.getState() == State.TIMED_WAITING)
								publishThread.interrupt();								
						}
					}					
				}
			}
		} catch (Exception e) {
			String errorMsg = Messages.getString("Error_MqttSinkOperator.21", tuple.toString()); //$NON-NLS-1$
			TRACE.log(TraceLevel.ERROR, errorMsg); //$NON-NLS-1$
			submitToErrorPort(errorMsg);
		}
	}
    
    /**
     * Process an incoming punctuation that arrived on the specified port.
     * @param stream Port the punctuation is arriving on.
     * @param mark The punctuation mark
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public void processPunctuation(StreamingInput<Tuple> stream,
    		Punctuation mark) throws Exception {
    	// TODO: If window punctuations are meaningful to the external system or data store, 
    	// insert code here to process the incoming punctuation.
    }

    /**
     * Shutdown this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                
        shutdown = true;
        mqttWrapper.disconnect();
        mqttWrapper.shutdown();
        
        // Must call super.shutdown()
        super.shutdown();
    }

    @Parameter(name="topic", description=SPLDocConstants.MQTTSINK_PARAM_TOPIC_DESC, optional=true)
	public void setTopics(String topic) {
		this.topic = topic;
		
		if (topic.startsWith("$")) //$NON-NLS-1$
		{
			topicAttributeName = topic.substring(1);
		}
	}

    @Parameter(name="qos", description=SPLDocConstants.MQTTSINK_PARAM_QOS_DESC, optional=true)
	public void setQos(int qos) {
		this.qos = qos;
	}

    public String getTopics() {
		return topic;
	}

	public int getQos() {
		return qos;
	}

	@Parameter(name="reconnectionBound", description=SPLDocConstants.MQTTSINK_PARAM_RECONN_BOUND_DESC, optional=true)
	public void setReconnectionBound(int reconnectionBound) {
		this.reconnectionBound = reconnectionBound;
	}
	
	@Parameter(name="period", description=SPLDocConstants.MQTTSINK_PARAM_PERIOD_DESC, optional=true)
	public void setPeriod(long period) {
		this.period = period;
	}
	
	public int getReconnectionBound() {
		return reconnectionBound;
	}
	
	public long getPeriod() {
		return period;
	}

	public boolean isRetain() {
		return retain;
	}

	@Parameter(name="retain", description=SPLDocConstants.MQTTSINK_PARAM_RETAIN_DESC, optional=true)
	public void setRetain(boolean retain) {
		this.retain = retain;
	}
	
	@Parameter(name="topicAttributeName", description=SPLDocConstants.MQTTSINK_PARAM_TOPIC_ATTR_NAME_DESC, optional=true)
	public void setTopicAttrName(String topicAttr) {
		this.topicAttributeName = topicAttr;
	}
	
	public String getTopicAttrName() {
		return topicAttributeName;
	}
	
	@Parameter(name="qosAttributeName", description=SPLDocConstants.MQTTSINK_PARAM_QOS_ATTR_NAME_DESC, optional=true)
	public void setQosAttributeName(String qosAttributeName) {
		this.qosAttributeName = qosAttributeName;
	}
	
	public String getQosAttributeName() {
		return qosAttributeName;
	}
	
	protected StreamingOutput<OutputTuple> getErrorOutputPort() {
		return getErrorPortFromContext(getOperatorContext());
	}
    
	private static StreamingOutput<OutputTuple> getErrorPortFromContext(OperatorContext opContext) {
		List<StreamingOutput<OutputTuple>> streamingOutputs = opContext.getStreamingOutputs();
		if (streamingOutputs.size() > 0) {
			return streamingOutputs.get(0);
		}
		return null;
	}
}