package edu.umich.qna.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import edu.umich.qna.DataSourceConnection;
import edu.umich.qna.QueryState;

public class DatabaseConnection implements DataSourceConnection {

	final Connection conn;
	
	DatabaseConnection(Connection conn) {
		this.conn = conn;
	}
	
	@Override
	public void disconnect() {
		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public QueryState executeQuery(String querySource,
			Map<Object, List<Object>> queryParameters) {
		
		QueryState dbQueryState = null;
		
		if (conn != null) {
			dbQueryState = new DatabaseQueryState(conn);
			
			if (!dbQueryState.initialize(querySource, queryParameters)) {
				dbQueryState = null;
			}
		}
		
		return dbQueryState;
	}

}
