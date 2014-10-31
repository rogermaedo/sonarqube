/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.server.db.migrations.v50;

import org.apache.commons.dbutils.DbUtils;
import org.sonar.api.utils.System2;
import org.sonar.core.persistence.Database;
import org.sonar.server.db.migrations.*;

import javax.annotation.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

/**
 * Used in the Active Record Migration 710
 *
 * @since 5.0
 */
public class ReplaceIssueFiltersProjectKeyByUuid extends BaseDataChange {

  public static final String OLD_COMPONENT_ROOTS_FIELDS = "componentRoots";
  public static final String NEW_COMPONENT_ROOTS_FIELDS = "componentRootUuids";
  private final System2 system;

  public ReplaceIssueFiltersProjectKeyByUuid(Database db, System2 system) {
    super(db);
    this.system = system;
  }

  @Override
  public void execute(final Context context) throws SQLException {
    final Date now = new Date(system.now());

    Connection connection = null;
    PreparedStatement pstmt = null;
    try {
      connection = openConnection();
      pstmt = connection.prepareStatement("SELECT p.uuid as uuid FROM projects p WHERE p.kee=?");

      MassUpdate massUpdate = context.prepareMassUpdate();
      massUpdate.select("SELECT f.id, f.data FROM issue_filters f WHERE f.data like '%componentRoots=%'");
      massUpdate.update("UPDATE issue_filters SET data=?, updated_at=? WHERE id=?");
      final PreparedStatement finalPstmt = pstmt;
      massUpdate.execute(new MassUpdate.Handler() {
        @Override
        public boolean handle(Select.Row row, SqlStatement update) throws SQLException {
          Long id = row.getLong(1);
          String data = row.getString(2);
          if (data == null) {
            return false;
          }
          update.setString(1, convertData(finalPstmt, data));
          update.setDate(2, now);
          update.setLong(3, id);
          return true;
        }
      });
    } finally {
      DbUtils.closeQuietly(connection);
      DbUtils.closeQuietly(pstmt);
    }
  }

  private String convertData(PreparedStatement pstmt, String data) throws SQLException {
    StringBuilder newFields = new StringBuilder();
    String[] fields = data.split("\\|");
    for (int i=0; i<fields.length; i++) {
      String field = fields[i];
      if (field.contains(OLD_COMPONENT_ROOTS_FIELDS)) {
        String[] componentRootValues = field.split("=");
        append(pstmt, newFields, componentRootValues.length == 2 ? componentRootValues[1] : null);
      } else {
        newFields.append(field);
      }
      if (i < fields.length - 1) {
        newFields.append("|");
      }
    }
    return newFields.toString();
  }

  private void append(PreparedStatement pstmt, StringBuilder newFields, @Nullable String projectKey) throws SQLException {
    if (projectKey != null) {
      pstmt.setString(1, projectKey);
      ResultSet rs = null;
      try {
        rs = pstmt.executeQuery();
        if (rs.next()) {
          String projectUuid = SqlUtil.getString(rs, "uuid");
          if (projectUuid != null) {
            newFields.append(NEW_COMPONENT_ROOTS_FIELDS).append("=").append(projectUuid);
          }
        }
      } finally {
        DbUtils.closeQuietly(rs);
      }
    }
  }

}
