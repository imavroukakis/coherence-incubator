/*
 * File: PartitionDepartingEvent.java
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

package com.oracle.coherence.common.events.partition;

import com.oracle.coherence.common.events.AbortablePhasedEvent;
import com.tangosol.net.partition.PartitionEvent;

/**
 * A {@link PartitionArrivingEvent} is a phased {@link PartitionTransferEvent} that captures the information
 * concerning the departure (ie: the loss of ownership) of a set of partitions.
 * <p>
 * Copyright (c) 2009. All Rights Reserved. Oracle Corporation.<br>
 * Oracle is a registered trademark of Oracle Corporation and/or its affiliates.
 *
 * @author Brian Oliver
 */
public class PartitionDepartingEvent extends AbstractPartitionTransferEvent implements AbortablePhasedEvent
{
    /**
     * The {@link Phase} of the {@link PartitionTransferEvent}.
     */
    private Phase phase;


    /**
     * Standard Constructor for Tangosol-based {@link PartitionEvent}s.
     *
     * @param partitionEvent the Tangosol based {@link PartitionEvent}
     */
    public PartitionDepartingEvent(PartitionEvent partitionEvent)
    {
        super(partitionEvent);

        switch (partitionEvent.getId())
        {
        case PartitionEvent.PARTITION_TRANSMIT_BEGIN :
            this.phase = Phase.Commenced;
            break;

        case PartitionEvent.PARTITION_TRANSMIT_COMMIT :
            this.phase = Phase.Completed;
            break;

        case PartitionEvent.PARTITION_TRANSMIT_ROLLBACK :
            this.phase = Phase.Aborted;
            break;
        }
    }


    /**
     * {@inheritDoc}
     */
    public Phase getPhase()
    {
        return phase;
    }


    /**
     * {@inheritDoc}
     */
    public String toString()
    {
        return String.format("PartitionDepartingEvent{partitionSet=%s, phase=%s, fromMember=%s, toMember=%s}",
                             getPartitionSet(),
                             getPhase(),
                             getMemberFrom(),
                             getMemberTo());
    }
}
