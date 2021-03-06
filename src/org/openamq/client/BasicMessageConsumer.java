package org.openamq.client;

import edu.emory.mathcs.backport.java.util.concurrent.SynchronousQueue;
import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;
import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicBoolean;
import edu.emory.mathcs.backport.java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openamq.AMQException;
import org.openamq.client.message.MessageFactoryRegistry;
import org.openamq.client.message.UnprocessedMessage;
import org.openamq.client.message.AbstractJMSMessage;
import org.openamq.client.message.AMQMessage;
import org.openamq.client.protocol.AMQProtocolHandler;
import org.openamq.client.state.listener.SpecificMethodFrameListener;
import org.openamq.framing.*;
import org.openamq.jms.MessageConsumer;
import org.openamq.jms.Session;

import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;

public class BasicMessageConsumer extends Closeable implements MessageConsumer
{
    private static final Logger _logger = LoggerFactory.getLogger(BasicMessageConsumer.class);

    /**
     * The connection being used by this consumer
     */
    private AMQConnection _connection;

    private String _messageSelector;

    private boolean _noLocal;

    private AMQDestination _destination;

    /**
     * When true indicates that a blocking receive call is in progress
     */
    private final AtomicBoolean _receiving = new AtomicBoolean(false);
    /**
     * Holds an atomic reference to the listener installed.
     */
    private final AtomicReference _messageListener = new AtomicReference();

    /**
     * The consumer tag allows us to close the consumer by sending a jmsCancel method to the
     * broker
     */
    private String _consumerTag;

    /**
     * We need to know the channel id when constructing frames
     */
    private int _channelId;

    /**
     * Used in the blocking receive methods to receive a message from
     * the Session thread. Argument true indicates we want strict FIFO semantics
     */
    private final SynchronousQueue _synchronousQueue = new SynchronousQueue(true);

    private MessageFactoryRegistry _messageFactory;

    private AMQSession _session;

    private AMQProtocolHandler _protocolHandler;

    /**
     * We need to store the "raw" field table so that we can resubscribe in the event of failover being required
     */
    private FieldTable _rawSelectorFieldTable;

    /**
     * We store the prefetch field in order to be able to reuse it when resubscribing in the event of failover
     */
    private int _prefetch;

    /**
     * We store the exclusive field in order to be able to reuse it when resubscribing in the event of failover
     */
    private boolean _exclusive;

    /**
     * The acknowledge mode in force for this consumer. Note that the AMQP protocol allows different ack modes
     * per consumer whereas JMS defines this at the session level, hence why we associate it with the consumer in our
     * implementation.
     */
    private int _acknowledgeMode;

    /**
     * Number of messages unacknowledged in DUPS_OK_ACKNOWLEDGE mode
     */
    private int _outstanding;

    /**
     * Tag of last message delievered, whoch should be acknowledged on commit in
     * transaction mode.
     */
    private long _lastDeliveryTag;

    BasicMessageConsumer(int channelId, AMQConnection connection, AMQDestination destination, String messageSelector,
                         boolean noLocal, MessageFactoryRegistry messageFactory, AMQSession session,
                         AMQProtocolHandler protocolHandler, FieldTable rawSelectorFieldTable, int prefetch,
                         boolean exclusive, int acknowledgeMode)
    {
        _channelId = channelId;
        _connection = connection;
        _messageSelector = messageSelector;
        _noLocal = noLocal;
        _destination = destination;
        _messageFactory = messageFactory;
        _session = session;
        _protocolHandler = protocolHandler;
        _rawSelectorFieldTable =rawSelectorFieldTable;
        _prefetch = prefetch;
        _exclusive = exclusive;
        _acknowledgeMode = acknowledgeMode;
    }

    public AMQDestination getDestination()
    {
        return _destination;
    }

    public String getMessageSelector() throws JMSException
    {
        return _messageSelector;
    }

    public MessageListener getMessageListener() throws JMSException
    {
        return (MessageListener) _messageListener.get();
    }

    public int getAcknowledgeMode()
    {
        return _acknowledgeMode;
    }

    private boolean isMessageListenerSet()
    {
        return _messageListener.get() != null;
    }

    public void setMessageListener(MessageListener messageListener) throws JMSException
    {
        checkNotClosed();

        //if the current listener is non-null and the session is not stopped, then
        //it is an error to call this method.

        //i.e. it is only valid to call this method if
        //
        //    (a) the session is stopped, in which case the dispatcher is not running
        //    OR
        //    (b) the listener is null AND we are not receiving synchronously at present
        //

        if(_session.isStopped())
        {
            _messageListener.set(messageListener);
            _logger.debug("Message listener set for destination " + _destination);
        }
        else
        {
            if (_receiving.get())
            {
                throw new javax.jms.IllegalStateException("Another thread is already receiving synchronously.");
            }
            if (!_messageListener.compareAndSet(null, messageListener))
            {
                throw new javax.jms.IllegalStateException("Attempt to alter listener while session is started.");
            }
            _logger.debug("Message listener set for destination " + _destination);

            if (messageListener != null)
            {
                //handle case where connection has already been started, and the dispatcher is blocked
                //doing a put on the _synchronousQueue
                Object msg = _synchronousQueue.poll();
                if (msg != null)
                {
                    AbstractJMSMessage jmsMsg = (AbstractJMSMessage) msg;
                    messageListener.onMessage(jmsMsg);
                    postDeliver(jmsMsg);
                }
            }
        }
    }

    private void acquireReceiving() throws JMSException
    {
        if (!_receiving.compareAndSet(false, true))
        {
            throw new javax.jms.IllegalStateException("Another thread is already receiving.");
        }
        if (isMessageListenerSet())
        {
            throw new javax.jms.IllegalStateException("A listener has already been set.");
        }
    }

    private void releaseReceiving(){
        _receiving.set(false);
    }

    public FieldTable getRawSelectorFieldTable()
    {
        return _rawSelectorFieldTable;
    }

    public int getPrefetch()
    {
        return _prefetch;
    }

    public boolean isNoLocal()
    {
        return _noLocal;
    }

    public boolean isExclusive()
    {
        return _exclusive;
    }

    public Message receive() throws JMSException
    {
        return receive(0);
    }

    public Message receive(long l) throws JMSException
    {
        checkNotClosed();

        acquireReceiving();

        try
        {
            Object o = null;
            if (l > 0)
            {
                o = _synchronousQueue.poll(l, TimeUnit.MILLISECONDS);
            }
            else
            {
                o = _synchronousQueue.take();
            }
            final AbstractJMSMessage m = returnMessageOrThrow(o);
            postDeliver(m);
            return m;
        }
        catch (InterruptedException e)
        {
            return null;
        }
        finally
        {
            releaseReceiving();
        }
    }

    public Message receiveNoWait() throws JMSException
    {
        checkNotClosed();

        acquireReceiving();

        try
        {
            Object o = _synchronousQueue.poll();
            final AbstractJMSMessage m = returnMessageOrThrow(o);
            postDeliver(m);
            return m;
        }
        finally
        {
            releaseReceiving();
        }
    }

    /**
     * We can get back either a Message or an exception from the queue. This method examines the argument and deals
     * with it by throwing it (if an exception) or returning it (in any other case).
     * @param o
     * @return a message only if o is a Message
     * @throws JMSException if the argument is a throwable. If it is a JMSException it is rethrown as is, but if not
     * a JMSException is created with the linked exception set appropriately
     */
    private AbstractJMSMessage returnMessageOrThrow(Object o)
            throws JMSException
    {
        // errors are passed via the queue too since there is no way of interrupting the poll() via the API.
        if (o instanceof Throwable)
        {
            JMSException e = new JMSException("Message consumer forcibly closed due to error: " + o);
            if (o instanceof Exception)
            {
                e.setLinkedException((Exception) o);
            }
            throw e;
        }
        else
        {
            return (AbstractJMSMessage) o;
        }
    }

    public void close() throws JMSException
    {
        synchronized (_connection.getFailoverMutex())
        {
            if (!_closed.getAndSet(true))
            {
                final AMQFrame cancelFrame = BasicCancelBody.createAMQFrame(_channelId, _consumerTag, false);

                try
                {
                    _protocolHandler.writeCommandFrameAndWaitForReply(cancelFrame,
                                                                      new SpecificMethodFrameListener(_channelId,
                                                                                                      BasicCancelOkBody.class));
                }
                catch (AMQException e)
                {
                    _logger.error("Error closing consumer: " + e, e);
                    throw new JMSException("Error closing consumer: " + e);
                }

                deregisterConsumer();
            }
        }
    }

    /**
     * Called when you need to invalidate a consumer. Used for example when failover has occurred and the
     * client has vetoed automatic resubscription.
     * The caller must hold the failover mutex.
     */
    void markClosed()
    {
        _closed.set(true);
        deregisterConsumer();
    }

    /**
     * Called from the AMQSession when a message has arrived for this consumer. This methods handles both the case
     * of a message listener or a synchronous receive() caller.
     * @param messageFrame the raw unprocessed mesage
     * @param channelId channel on which this message was sent
     */
    void notifyMessage(UnprocessedMessage messageFrame, int channelId)
    {
        if (_logger.isDebugEnabled())
        {
            _logger.debug("notifyMessage called with message number " + messageFrame.deliverBody.deliveryTag);
        }
        try
        {
            AbstractJMSMessage jmsMessage = _messageFactory.createMessage(messageFrame.deliverBody.deliveryTag,
                                                                       messageFrame.deliverBody.redelivered,
                                                                       messageFrame.contentHeader,
                                                                       messageFrame.bodies);

            _logger.debug("Message is of type: " + jmsMessage.getClass().getName());

            preDeliver(jmsMessage);

            if (isMessageListenerSet())
            {
                //we do not need a lock around the test above, and the dispatch below as it is invalid
                //for an application to alter an installed listener while the session is started
                getMessageListener().onMessage(jmsMessage);
                postDeliver(jmsMessage);
            }
            else
            {
                _synchronousQueue.put(jmsMessage);
            }
        }
        catch (Exception e)
        {
            _logger.error("Caught exception (dump follows) - ignoring...", e);
        }
    }

    private void preDeliver(AbstractJMSMessage msg)
    {
        switch (_acknowledgeMode)
        {
            case Session.PRE_ACKNOWLEDGE:
                _session.acknowledgeMessage(msg.getDeliveryTag(), false);
                break;
            case Session.CLIENT_ACKNOWLEDGE:
                // we set the session so that when the user calls acknowledge() it can call the method on session
                // to send out the appropriate frame
                msg.setAMQSession(_session);
                break;
        }
    }

    private void postDeliver(AbstractJMSMessage msg)
    {
        switch (_acknowledgeMode)
        {
            case Session.DUPS_OK_ACKNOWLEDGE:
                if(++_outstanding >= _prefetch)
                {
                    _session.acknowledgeMessage(msg.getDeliveryTag(), true);
                }
                break;
            case Session.AUTO_ACKNOWLEDGE:
                _session.acknowledgeMessage(msg.getDeliveryTag(), false);
                break;
            case Session.SESSION_TRANSACTED:
                _lastDeliveryTag = msg.getDeliveryTag();
                break;

        }
    }

    void commit()
    {
        if(_lastDeliveryTag >= 0)
        {
            _session.acknowledgeMessage(_lastDeliveryTag, true);
            _lastDeliveryTag = -1;
        }
    }

    void notifyError(Throwable cause)
    {
        _closed.set(true);

        // we have no way of propagating the exception to a message listener - a JMS limitation - so we
        // deal with the case where we have a synchronous receive() waiting for a message to arrive
        if (!isMessageListenerSet())
        {
            // offer only succeeds if there is a thread waiting for an item from the queue
            if (_synchronousQueue.offer(cause))
            {
                _logger.debug("Passed exception to synchronous queue for propagation to receive()");
            }
        }
        deregisterConsumer();
    }

    /**
     * Perform cleanup to deregister this consumer. This occurs when closing the consumer in both the clean
     * case and in the case of an error occurring.
     */
    private void deregisterConsumer()
    {
        _session.deregisterConsumer(_consumerTag);
    }

    public String getConsumerTag()
    {
        return _consumerTag;
    }

    public void setConsumerTag(String consumerTag)
    {
        _consumerTag = consumerTag;
    }
}
