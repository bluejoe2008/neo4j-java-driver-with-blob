/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.driver.internal.async;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import org.neo4j.driver.Session;
import org.neo4j.driver.Statement;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.async.StatementResultCursor;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.internal.Bookmarks;
import org.neo4j.driver.internal.BookmarksHolder;
import org.neo4j.driver.internal.cursor.InternalStatementResultCursor;
import org.neo4j.driver.internal.cursor.RxStatementResultCursor;
import org.neo4j.driver.internal.messaging.BoltProtocol;
import org.neo4j.driver.internal.spi.Connection;
import org.neo4j.driver.internal.util.Futures;

import static org.neo4j.driver.internal.util.Futures.completedWithNull;
import static org.neo4j.driver.internal.util.Futures.failedFuture;

public class ExplicitTransaction
{
    private enum State
    {
        /** The transaction is running with no explicit success or failure marked */
        ACTIVE,

        /** Running, user marked for success, meaning it'll value committed */
        MARKED_SUCCESS,

        /** User marked as failed, meaning it'll be rolled back. */
        MARKED_FAILED,

        /**
         * This transaction has been terminated either because of explicit {@link Session#reset()} or because of a
         * fatal connection error.
         */
        TERMINATED,

        /** This transaction has successfully committed */
        COMMITTED,

        /** This transaction has been rolled back */
        ROLLED_BACK
    }

    private final Connection connection;
    private final BoltProtocol protocol;
    private final BookmarksHolder bookmarksHolder;
    private final ResultCursorsHolder resultCursors;

    private volatile State state = State.ACTIVE;

    public ExplicitTransaction( Connection connection, BookmarksHolder bookmarksHolder )
    {
        this.connection = connection;
        this.protocol = connection.protocol();
        this.bookmarksHolder = bookmarksHolder;
        this.resultCursors = new ResultCursorsHolder();
    }

    public CompletionStage<ExplicitTransaction> beginAsync( Bookmarks initialBookmarks, TransactionConfig config )
    {
        return protocol.beginTransaction( connection, initialBookmarks, config )
                .handle( ( ignore, beginError ) ->
                {
                    if ( beginError != null )
                    {
                        // release connection if begin failed, transaction can't be started
                        connection.release();
                        throw Futures.asCompletionException( beginError );
                    }
                    return this;
                } );
    }

    public void success()
    {
        if ( state == State.ACTIVE )
        {
            state = State.MARKED_SUCCESS;
        }
    }

    public void failure()
    {
        if ( state == State.ACTIVE || state == State.MARKED_SUCCESS )
        {
            state = State.MARKED_FAILED;
        }
    }

    public CompletionStage<Void> closeAsync()
    {
        if ( state == State.MARKED_SUCCESS )
        {
            return commitAsync();
        }
        else if ( state != State.COMMITTED && state != State.ROLLED_BACK )
        {
            return rollbackAsync();
        }
        else
        {
            return completedWithNull();
        }
    }

    public CompletionStage<Void> commitAsync()
    {
        if ( state == State.COMMITTED )
        {
            return completedWithNull();
        }
        else if ( state == State.ROLLED_BACK )
        {
            return failedFuture( new ClientException( "Can't commit, transaction has been rolled back" ) );
        }
        else
        {
            return resultCursors.retrieveNotConsumedError()
                    .thenCompose( error -> doCommitAsync().handle( handleCommitOrRollback( error ) ) )
                    .whenComplete( ( ignore, error ) -> transactionClosed( error == null ) );
        }
    }

    public CompletionStage<Void> rollbackAsync()
    {
        if ( state == State.COMMITTED )
        {
            return failedFuture( new ClientException( "Can't rollback, transaction has been committed" ) );
        }
        else if ( state == State.ROLLED_BACK )
        {
            return completedWithNull();
        }
        else
        {
            return resultCursors.retrieveNotConsumedError()
                    .thenCompose( error -> doRollbackAsync().handle( handleCommitOrRollback( error ) ) )
                    .whenComplete( ( ignore, error ) -> transactionClosed( false ) );
        }
    }

    public CompletionStage<StatementResultCursor> runAsync( Statement statement, boolean waitForRunResponse )
    {
        ensureCanRunQueries();
        CompletionStage<InternalStatementResultCursor> cursorStage =
                protocol.runInExplicitTransaction( connection, statement, this, waitForRunResponse ).asyncResult();
        resultCursors.add( cursorStage );
        return cursorStage.thenApply( cursor -> cursor );
    }

    public CompletionStage<RxStatementResultCursor> runRx( Statement statement )
    {
        ensureCanRunQueries();
        CompletionStage<RxStatementResultCursor> cursorStage =
                protocol.runInExplicitTransaction( connection, statement, this, false ).rxResult();
        resultCursors.add( cursorStage );
        return cursorStage;
    }

    public boolean isOpen()
    {
        return state != State.COMMITTED && state != State.ROLLED_BACK;
    }

    public void markTerminated()
    {
        state = State.TERMINATED;
    }

    public Connection connection()
    {
        return connection;
    }

    private void ensureCanRunQueries()
    {
        if ( state == State.COMMITTED )
        {
            throw new ClientException( "Cannot run more statements in this transaction, it has been committed" );
        }
        else if ( state == State.ROLLED_BACK )
        {
            throw new ClientException( "Cannot run more statements in this transaction, it has been rolled back" );
        }
        else if ( state == State.MARKED_FAILED )
        {
            throw new ClientException( "Cannot run more statements in this transaction, it has been marked for failure. " +
                    "Please either rollback or close this transaction" );
        }
        else if ( state == State.TERMINATED )
        {
            throw new ClientException( "Cannot run more statements in this transaction, " +
                    "it has either experienced an fatal error or was explicitly terminated" );
        }
    }

    private CompletionStage<Void> doCommitAsync()
    {
        if ( state == State.TERMINATED )
        {
            return failedFuture( new ClientException( "Transaction can't be committed. " +
                                                      "It has been rolled back either because of an error or explicit termination" ) );
        }
        return protocol.commitTransaction( connection ).thenAccept( bookmarksHolder::setBookmarks );
    }

    private CompletionStage<Void> doRollbackAsync()
    {
        if ( state == State.TERMINATED )
        {
            return completedWithNull();
        }
        return protocol.rollbackTransaction( connection );
    }

    private static BiFunction<Void,Throwable,Void> handleCommitOrRollback( Throwable cursorFailure )
    {
        return ( ignore, commitOrRollbackError ) ->
        {
            CompletionException combinedError = Futures.combineErrors( cursorFailure, commitOrRollbackError );
            if ( combinedError != null )
            {
                throw combinedError;
            }
            return null;
        };
    }

    private void transactionClosed( boolean isCommitted )
    {
        if ( isCommitted )
        {
            state = State.COMMITTED;
        }
        else
        {
            state = State.ROLLED_BACK;
        }
        connection.release(); // release in background
    }
}
