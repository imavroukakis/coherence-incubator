/*
 * File: QueueSubscriber.java
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * The contents of this file are subject to the terms and conditions of 
 * the Common Development and Distribution License 1.0 (the "License").
 *
 * You may not use this file except in compliance with the License.
 *
 * You can obtain a copy of the License by consulting the LICENSE.txt file
 * distributed with this file, or by consulting https://oss.oracle.com/licenses/CDDL
 *
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file LICENSE.txt.
 *
 * MODIFICATIONS:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 */

package com.oracle.coherence.patterns.messaging;

import com.oracle.coherence.common.logging.Logger;
import com.oracle.coherence.patterns.messaging.entryprocessors.AcknowledgeMessageProcessor;
import com.oracle.coherence.patterns.messaging.entryprocessors.AcknowledgeSubscriptionMessagesProcessor;
import com.oracle.coherence.patterns.messaging.entryprocessors.RequestMessageFromQueueProcessor;
import com.oracle.coherence.patterns.messaging.entryprocessors.RequestMessageProcessor;
import com.oracle.coherence.patterns.messaging.entryprocessors.RollbackMessageProcessor;
import com.oracle.coherence.patterns.messaging.entryprocessors.SubscriptionRollbackProcessor;
import com.tangosol.net.CacheFactory;
import com.tangosol.net.NamedCache;
import com.tangosol.util.processor.ExtractorProcessor;

import java.util.ArrayList;

/**
 * A specific implementation of {@link Subscriber} for {@link Queue}s.
 * <p>
 * Copyright (c) 2008. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Brian Oliver
 * @author Paul Mackin
 */
class QueueSubscriber extends AbstractSubscriber<QueueSubscription>
{
    /**
     * Package Level Constructor.
     *
     * @param messagingSession messaging session
     * @param subscriptionIdentifier subscription identifier
     */
    QueueSubscriber(MessagingSession       messagingSession,
                    SubscriptionIdentifier subscriptionIdentifier)
    {
        super(messagingSession, subscriptionIdentifier);

    }


    /**
     * {@inheritDoc}
     */
    public Object getMessage()
    {
        // ensure that the subscriber is still active
        ensureActive();

        // ensure that we have a subscription from which we can retrieve
        // messages
        ensureSubscription();

        // Request a message from the {@link Queue}
        NamedCache destinationCache = CacheFactory.getCache(Destination.CACHENAME);

        destinationCache.invoke(getDestinationIdentifier(),
                                new RequestMessageFromQueueProcessor(getSubscriptionIdentifier()));

        // wait for the message to arrive
        QueueSubscription subscription = getNextSubscriptionUpdate();

        // ensure the subscription has not been removed/shutdown
        ensureSubscription();

        MessageIdentifier messageIdentifier = subscription.getVisibleMessageTracker().getLast();

        if (messageIdentifier == null)
        {
            Logger.log(Logger.ERROR,
                       "Queue Subscriber.getMessage got null messageIdentifier for subscription %s\n",
                       subscription.getIdentifier().toString());

            return null;
        }

        // get the message
        int     partitionId           = messageIdentifier.getPartitionId();
        long    messageSequenceNumber = messageIdentifier.getMessageSequenceNumber();

        Message message;

        if (isAutoCommitting())
        {
            // retrieve and acknowledge the message on behalf of this subscriber
            message =
                (Message) CacheFactory.getCache(Message.CACHENAME)
                    .invoke(Message.getKey(getDestinationIdentifier(), partitionId, messageSequenceNumber),
                            new AcknowledgeMessageProcessor(getSubscriptionIdentifier()));

            if (message == null)
            {
                Logger.log(Logger.ERROR,
                           "AcknowledgeMessageProcessor returned null message for subscription %s\n",
                           subscription.getIdentifier().toString());

                return null;
            }

            if (!verifyMessageSequence(message))
            {
                // verifyMessageSequence will log an error
                return null;
            }

            // let the subscriber know that we've acknowledged the message
            MessageTracker messageTracker = new DefaultMessageTracker();

            messageTracker.add(messageIdentifier);
            CacheFactory.getCache(Subscription.CACHENAME).invoke(getSubscriptionIdentifier(),
                                                                 new AcknowledgeSubscriptionMessagesProcessor(messageTracker));

        }
        else
        {
            // retrieve the message
            message =
                (Message) CacheFactory.getCache(Message.CACHENAME)
                    .invoke(Message.getKey(getDestinationIdentifier(), partitionId, messageSequenceNumber),
                            new RequestMessageProcessor(getSubscriptionIdentifier()));

            if (message == null)
            {
                Logger.log(Logger.ERROR,
                           "RequestMessageProcessor returned null message for subscription %s\n",
                           subscription.getIdentifier().toString());

                return null;
            }

            if (!verifyMessageSequence(message))
            {
                // verifyMessageSequence will log an error
                return null;
            }
        }

        return message.getPayload();

    }


    /**
     * {@inheritDoc}
     */
    public void commit()
    {
        ensureActive();

        if (!isAutoCommitting())
        {
            // ensure that we have a subscription from which we can retrieve
            // messages
            ensureSubscription();

            // determine the range of messages that are visible
            // (these are the ones we must acknowledge)
            MessageTracker messageTracker =
                (MessageTracker) CacheFactory.getCache(Subscription.CACHENAME).invoke(getSubscriptionIdentifier(),
                                                                                      new ExtractorProcessor("getVisibleMessageTracker"));

            // send the AcknowledgeMessageProcessor to all of the visible
            // messages
            ArrayList<MessageKey> messageKeys = messageTracker.getMessageKeys(getDestinationIdentifier());

            CacheFactory.getCache(Message.CACHENAME).invokeAll(messageKeys,
                                                               new AcknowledgeMessageProcessor(getSubscriptionIdentifier()));

            // let the subscriber know that we've acknowledged all messages
            CacheFactory.getCache(Subscription.CACHENAME).invoke(getSubscriptionIdentifier(),
                                                                 new AcknowledgeSubscriptionMessagesProcessor(messageTracker));
        }

    }


    /**
     * {@inheritDoc}
     */
    public void rollback()
    {
        ensureActive();

        resetMessageSequenceVerification();

        if (!isAutoCommitting())
        {
            // ensure that we have a subscription from which we can retrieve
            // messages
            ensureSubscription();

            // determine the range of messages that are visible
            // (these are the ones we must rollback)
            MessageTracker messageTracker =
                (MessageTracker) CacheFactory.getCache(Subscription.CACHENAME).invoke(getSubscriptionIdentifier(),
                                                                                      new ExtractorProcessor("getVisibleMessageTracker"));

            NamedCache destinationCache = CacheFactory.getCache(Destination.CACHENAME);

            destinationCache.invoke(getDestinationIdentifier(),
                                    new RollbackMessageProcessor(getSubscriptionIdentifier(), messageTracker));

            // clear the delivered list for the subscription
            CacheFactory.getCache(Subscription.CACHENAME).invoke(getSubscriptionIdentifier(),
                                                                 new SubscriptionRollbackProcessor());
        }
    }


    /**
     * {@inheritDoc}
     */
    public void release()
    {
        unsubscribe();
    }
}
