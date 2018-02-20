/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;

import org.neo4j.driver.internal.messaging.Message;
import org.neo4j.driver.internal.messaging.MessageFormat;

public class MessageToByteBufWriter
{
    private final MessageFormat messageFormat;

    public MessageToByteBufWriter( MessageFormat messageFormat )
    {
        this.messageFormat = messageFormat;
    }

    public ByteBuf asByteBuf( Message message )
    {
        try
        {
            ByteBuf buf = Unpooled.buffer();
            ByteBufOutput output = new ByteBufOutput( buf );
            messageFormat.newWriter( output, true ).write( message );
            return buf;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }
}
