/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005-2005 The Eigenbase Project
// Copyright (C) 2005-2005 Disruptive Tech
// Copyright (C) 2005-2005 LucidEra, Inc.
// Portions Copyright (C) 2003-2005 John V. Sichi
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package net.sf.farrago.syslib;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

import net.sf.farrago.db.FarragoDbSingleton;
import net.sf.farrago.session.FarragoSession;
import net.sf.farrago.session.FarragoSessionExecutingStmtInfo;
import net.sf.farrago.session.FarragoSessionInfo;
import net.sf.farrago.session.FarragoSessionVariables;


/**
 * FarragoManagementUDR is a set of user-defined routines providing
 * access to information about the running state of Farrago,
 * intended for management purposes - such as a list of currently
 * executing statements.  The UDRs are used to create views in
 * initsql/createMgmtViews.sql.
 *
 * @author Jason Ouellette
 * @version $Id$
 */
public abstract class FarragoManagementUDR
{
    //~ Methods ---------------------------------------------------------------

    /**
     * Populates a table of information on currently executing statements.
     */
    public static void statements(PreparedStatement resultInserter)
        throws SQLException
    {
        List<FarragoSession> sessions = FarragoDbSingleton
                .getSessions();
        for (FarragoSession s : sessions) {
            FarragoSessionInfo info = s.getSessionInfo();
            List<Long> ids = info.getExecutingStmtIds();
            for (long id : ids) {
                FarragoSessionExecutingStmtInfo stmtInfo =
                    info.getExecutingStmtInfo(id);
                if (stmtInfo != null) {
                    int i = 0;
                    resultInserter.setLong(++i, id);
                    resultInserter.setLong(++i, info.getId());
                    resultInserter.setString(
                        ++i,
                        stmtInfo.getSql());
                    resultInserter.setTimestamp(
                        ++i,
                        new Timestamp(stmtInfo.getStartTime()));
                    resultInserter.setString(
                        ++i,
                        Arrays.asList(stmtInfo.getParameters()).toString());
                    resultInserter.executeUpdate();
                }
            }
        }
    }

    /**
     * Populates a table of catalog objects in use by active statements.
     *
     * @param resultInserter
     * @throws SQLException
     */
    public static void objectsInUse(PreparedStatement resultInserter)
        throws SQLException
    {
        List<FarragoSession> sessions = FarragoDbSingleton.getSessions();
        for (FarragoSession s : sessions) {
            FarragoSessionInfo info = s.getSessionInfo();
            List<Long> ids = info.getExecutingStmtIds();
            for (long id : ids) {
                FarragoSessionExecutingStmtInfo stmtInfo =
                    info.getExecutingStmtInfo(id);
                if (stmtInfo != null) {
                    List<String> mofIds = stmtInfo.getObjectsInUse();
                    for (String mofId : mofIds) {
                        int i = 0;
                        resultInserter.setLong(++i, id);
                        resultInserter.setString(++i, mofId);
                        resultInserter.executeUpdate();
                    }
                }
            }
        }
    }

    /**
     * Populates a table of currently active sessions.
     *
     * @param resultInserter
     * @throws SQLException
     */
    public static void sessions(PreparedStatement resultInserter)
        throws SQLException
    {
        List<FarragoSession> sessions = FarragoDbSingleton.getSessions();
        for (FarragoSession s : sessions) {
            int i = 0;
            FarragoSessionVariables v = s.getSessionVariables();
            FarragoSessionInfo info = s.getSessionInfo();
            resultInserter.setLong(
                ++i,
                info.getId());
            resultInserter.setString(
                ++i,
                s.getUrl());
            resultInserter.setString(++i, v.currentUserName);
            resultInserter.setString(++i, v.currentRoleName);
            resultInserter.setString(++i, v.sessionUserName);
            resultInserter.setString(++i, v.systemUserName);
            resultInserter.setString(++i, v.catalogName);
            resultInserter.setString(++i, v.schemaName);
            resultInserter.setBoolean(
                ++i,
                s.isClosed());
            resultInserter.setBoolean(
                ++i,
                s.isAutoCommit());
            resultInserter.setBoolean(
                ++i,
                s.isTxnInProgress());
            resultInserter.executeUpdate();
        }
    }
}