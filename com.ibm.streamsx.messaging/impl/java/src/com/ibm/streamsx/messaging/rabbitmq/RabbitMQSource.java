/* Generated by Streams Studio: March 26, 2014 2:09:26 PM EDT */
/*******************************************************************************
 * Copyright (C) 2015, MOHAMED-ALI SAID
 * All Rights Reserved
 *******************************************************************************/
package com.ibm.streamsx.messaging.rabbitmq;

//import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeoutException;

import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;

import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import java.util.logging.Logger;

/**
 * This operator was originally contributed by Mohamed-Ali Said @saidmohamedali
 * A source operator that does not receive any input streams and produces new
 * tuples. The method <code>produceTuples</code> is called to begin submitting
 * tuples.
 * <P>
 * For a source operator, the following event methods from the Operator
 * interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to
 * process and submit tuples</li>
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any
 * time, such as a request to stop a PE or cancel a job. Thus the shutdown() may
 * occur while the operator is processing tuples, punctuation marks, or even
 * during port ready notification.</li>
 * </ul>
 * <p>
 * With the exception of operator initialization, all the other events may occur
 * concurrently with each other, which lead to these methods being called
 * concurrently by different threads.
 * </p>
 */
@OutputPorts(@OutputPortSet(cardinality = 1, optional = false, description = "Messages received from Kafka are sent on this output port."))
@PrimitiveOperator(name = "RabbitMQSource", description = "something")
public class RabbitMQSource extends RabbitBaseOper {

	private List<String> routingKeys = new ArrayList<String>();

	private final Logger trace = Logger.getLogger(RabbitBaseOper.class
			.getCanonicalName());
	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples
	 */
	private Thread processThread;
	private String queueName = "";
	/**
	 * Initialize this operator. Called once before any tuples are processed.
	 * 
	 * @param context
	 *            OperatorContext for this operator.
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
		super.initialize(context);
		super.initSchema(getOutput(0).getStreamSchema());
		trace.log(TraceLevel.INFO, this.getClass().getName() + "Operator " + context.getName()
				+ " initializing in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());

		initRabbitChannel();
		// produce tuples returns immediately, but we don't want ports to close
		createAvoidCompletionThread();

		processThread = getOperatorContext().getThreadFactory().newThread(
				new Runnable() {

					@Override
					public void run() {
						try {
							produceTuples();
							// rabbitMQWrapper.Consume();
						} catch (Exception e) {
							e.printStackTrace(); // Logger.getLogger(this.getClass()).error("Operator error",
													// e);
						}
					}

				});

		processThread.setDaemon(false);
	}

	private void initRabbitChannel() throws IOException {
		if (queueName == "") {
			queueName = channel.queueDeclare().getQueue();
		}
		
		if (routingKeys.isEmpty())
			routingKeys.add("");//receive all messages by default

		for (String routingKey : routingKeys){
			channel.queueBind(queueName, exchangeName, routingKey);
			trace.log(TraceLevel.INFO, "Queue: " + queueName + " Exchange: " + exchangeName);
		}
	}

	/**
	 * Notification that initialization is complete and all input and output
	 * ports are connected and ready to receive and submit tuples.
	 * 
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void allPortsReady() throws Exception {
		processThread.start();
	}

	/**
	 * Submit new tuples to the output stream
	 * 
	 * @throws Exception
	 *             if an error occurs while submitting a tuple
	 */
	private void produceTuples() throws Exception {
		trace.log(TraceLevel.INFO, "Producing tuples!");
		Consumer consumer = new DefaultConsumer(channel) {
			@Override
			public void handleDelivery(String consumerTag, Envelope envelope,
					AMQP.BasicProperties properties, byte[] body)
					throws IOException {
				String message = new String(body, "UTF-8");
				StreamingOutput<OutputTuple> out = getOutput(0);
				
				
				
				OutputTuple tuple = out.newTuple();
				trace.log(TraceLevel.INFO, "Schema: " + tuple.getStreamSchema().getAttributeNames().toString());

				tuple.setString(messageAH.getName(), message);
				
				if (routingKeyAH.isAvailable()) {
					tuple.setString(routingKeyAH.getName(),
							envelope.getRoutingKey());
					trace.log(TraceLevel.INFO, routingKeyAH.getName() + ":"
							+ envelope.getRoutingKey());
				} 				
				
				if (messageHeaderAH.isAvailable()){
					Map<String, Object> msgHeader = properties.getHeaders();
					if (msgHeader != null && !msgHeader.isEmpty()){
						Map<String, String> headers = new HashMap<String,String>();
						Iterator<Entry<String,Object>> it = msgHeader.entrySet().iterator();
						while (it.hasNext()){
							Map.Entry<String, Object> pair = it.next();
							trace.log(TraceLevel.INFO, "Header: " + pair.getKey() + ":" + pair.getValue().toString());
							headers.put(pair.getKey(), pair.getValue().toString());
						}
						tuple.setMap(messageHeaderAH.getName(), headers);
					}
				}

				trace.log(TraceLevel.INFO, "message: " + message);
				// Submit tuple to output stream
				try {
					out.submit(tuple);
				} catch (Exception e) {
					trace.log(TraceLevel.INFO, "Catching submit exception");
					e.printStackTrace();
				}
			}
		};
		channel.basicConsume(queueName, true, consumer);
	}
	
	@Parameter(optional = true, description = "Exchange Name.")
	public void setRoutingKey(List<String> values) {
		if(values!=null)
			routingKeys.addAll(values);
	}	
	
	@Parameter(optional = true, description = "Name of the queue. Main reason to specify is to facilitate parallel consuming. Default is a random queue name..")
	public void setQueueName(String value) {
		queueName = value;
	}

	/**
	 * Shutdown this operator, which will interrupt the thread executing the
	 * <code>produceTuples()</code> method.
	 * 
	 * @throws TimeoutException
	 * @throws IOException
	 */
	public synchronized void shutdown() throws IOException, TimeoutException {
		// rabbitMQWrapper.logout();
		if (processThread != null) {
			processThread.interrupt();
			processThread = null;
		}
		OperatorContext context = getOperatorContext();
		trace.log(TraceLevel.ALL, "Operator " + context.getName()
				+ " shutting down in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());
		// Must call super.shutdown()
		super.shutdown();
	}
}
