package de.cgoit.faktura;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Describes a migrated schema as normalized text: tables, columns (sorted by name), keys, checks, indexes and all rows.
 * Column order, names of automatically named keys and quoting are left out, so two schemas that behave the same give
 * the same text. Used by {@link SchemaTest} to compare the database against the checked-in description.
 */
final class SchemaDescriber {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SchemaDescriber() {
    }

    static String describeSqlite(Connection connection) throws SQLException {
        var out = new StringBuilder();

        for (var table : sqliteTables(connection)) {
            out.append("TABLE ").append(table.name()).append('\n');
            sqliteColumns(connection, table.name(), out);
            sqliteForeignKeys(connection, table.name(), out);
            sqliteIndexes(connection, table.name(), out);
            sqliteChecks(table.sql(), out);
            rows(connection, table.name(), out);
            out.append('\n');
        }

        return out.toString();
    }

    static String describePostgres(Connection connection) throws SQLException {
        var out = new StringBuilder();

        for (var table : query(connection, """
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name""")) {
            var name = table.get(0);

            out.append("TABLE ").append(name).append('\n');

            for (var column : query(connection, """
                    SELECT column_name, data_type, character_maximum_length, is_nullable, column_default
                    FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '%s'
                    ORDER BY column_name""".formatted(name))) {
                out.append("  COLUMN ").append(column.get(0)).append(' ').append(column.get(1))
                        .append(column.get(2).equals("NULL") ? "" : "(" + column.get(2) + ")")
                        .append(column.get(3).equals("NO") ? " NOT NULL" : "")
                        .append(column.get(4).equals("NULL") ? "" : " DEFAULT " + column.get(4))
                        .append('\n');
            }

            var constraints = new ArrayList<String>();

            for (var constraint : query(connection, """
                    SELECT contype, conname, pg_get_constraintdef(c.oid)
                    FROM pg_constraint c WHERE conrelid = '%s'::regclass""".formatted(name))) {
                // keys get their name from the table (and renames keep old names), so only checks are named
                constraints.add("  " + (constraint.get(0).equals("c") ? constraint.get(1) + " " : "")
                        + normalize(constraint.get(2)));
            }

            constraints.stream().sorted().forEach(line -> out.append(line).append('\n'));

            for (var index : query(connection, """
                    SELECT indexdef FROM pg_indexes i
                    WHERE schemaname = 'public' AND tablename = '%s'
                      AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conname = i.indexname)
                    ORDER BY indexname""".formatted(name))) {
                out.append("  INDEX ").append(normalize(index.get(0))).append('\n');
            }

            rows(connection, name, out);
            out.append('\n');
        }

        return out.toString();
    }

    private record SqliteTable(String name, String sql) {
    }

    private static List<SqliteTable> sqliteTables(Connection connection) throws SQLException {
        var tables = new ArrayList<SqliteTable>();

        for (var row : query(connection, """
                SELECT name, sql FROM sqlite_master
                WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'
                ORDER BY name""")) {
            tables.add(new SqliteTable(row.get(0), row.get(1)));
        }

        return tables;
    }

    private static void sqliteColumns(Connection connection, String table, StringBuilder out) throws SQLException {
        var columns = query(connection, "PRAGMA table_info('" + table + "')");
        var primaryKey = new ArrayList<List<String>>();

        columns.stream()
                .sorted(Comparator.comparing(row -> row.get(1)))
                .forEach(row -> out.append("  COLUMN ").append(row.get(1)).append(' ').append(row.get(2).toUpperCase())
                        .append(row.get(3).equals("1") ? " NOT NULL" : "")
                        .append(row.get(4).equals("NULL") ? "" : " DEFAULT " + row.get(4))
                        .append('\n'));

        columns.stream().filter(row -> !row.get(5).equals("0")).forEach(primaryKey::add);

        if (!primaryKey.isEmpty()) {
            out.append("  PRIMARY KEY (")
                    .append(String.join(", ", primaryKey.stream()
                            .sorted(Comparator.comparing(row -> Integer.parseInt(row.get(5))))
                            .map(row -> row.get(1))
                            .toList()))
                    .append(")\n");
        }
    }

    private static void sqliteForeignKeys(Connection connection, String table, StringBuilder out) throws SQLException {
        var lines = new ArrayList<String>();

        for (var row : query(connection, "PRAGMA foreign_key_list('" + table + "')")) {
            lines.add("  FOREIGN KEY (" + row.get(3) + ") REFERENCES " + row.get(2) + "(" + row.get(4) + ")");
        }

        lines.stream().sorted().forEach(line -> out.append(line).append('\n'));
    }

    private static void sqliteIndexes(Connection connection, String table, StringBuilder out) throws SQLException {
        var lines = new ArrayList<String>();

        for (var index : query(connection, "PRAGMA index_list('" + table + "')")) {
            var name = index.get(1);
            var origin = index.get(3);
            var columns = String.join(", ", query(connection, "PRAGMA index_info('" + name + "')").stream()
                    .map(row -> row.get(2))
                    .toList());

            if (origin.equals("pk")) {
                continue;
            }

            if (origin.equals("u")) {
                lines.add("  UNIQUE (" + columns + ")");
                continue;
            }

            var sql = query(connection, "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = '" + name + "'")
                    .get(0).get(0);

            lines.add("  INDEX " + name + (index.get(2).equals("1") ? " UNIQUE" : "") + " (" + columns + ")"
                    + (index.get(4).equals("1") ? " WHERE " + normalize(sql.substring(sql.toUpperCase().lastIndexOf(" WHERE ") + 7)) : ""));
        }

        lines.stream().sorted().forEach(line -> out.append(line).append('\n'));
    }

    private static void sqliteChecks(String sql, StringBuilder out) {
        var upper = sql.toUpperCase();
        var lines = new ArrayList<String>();
        var from = 0;

        while (true) {
            var at = upper.indexOf("CHECK", from);

            if (at < 0) {
                break;
            }

            var open = sql.indexOf('(', at);
            var depth = 0;
            var end = open;

            for (; end < sql.length(); end++) {
                if (sql.charAt(end) == '(') {
                    depth++;
                } else if (sql.charAt(end) == ')' && --depth == 0) {
                    break;
                }
            }

            var before = normalize(sql.substring(Math.max(0, at - 80), at));
            var match = Pattern.compile("CONSTRAINT (\\w+) $").matcher(before);
            var name = match.find() ? match.group(1) : "";

            lines.add("  CHECK " + name + " " + normalize(sql.substring(open, end + 1)));
            from = end;
        }

        lines.stream().sorted().forEach(line -> out.append(line).append('\n'));
    }

    private static void rows(Connection connection, String table, StringBuilder out) throws SQLException {
        try (var statement = connection.createStatement();
                var resultSet = statement.executeQuery("SELECT * FROM " + table)) {
            var metaData = resultSet.getMetaData();
            var names = new ArrayList<String>();

            for (var i = 1; i <= metaData.getColumnCount(); i++) {
                names.add(metaData.getColumnName(i));
            }

            var sorted = names.stream().sorted().toList();
            var lines = new ArrayList<String>();

            while (resultSet.next()) {
                var line = new StringBuilder("  ROW");

                for (var name : sorted) {
                    var value = resultSet.getString(name);

                    line.append(' ').append(name).append('=')
                            .append(value == null ? "NULL" : "'" + value.replace("\\", "\\\\").replace("\n", "\\n") + "'");
                }

                lines.add(line.toString());
            }

            lines.stream().sorted().forEach(line -> out.append(line).append('\n'));
        }
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text.replace("\"", "").replace("`", "")).replaceAll(" ").strip();
    }

    private static List<List<String>> query(Connection connection, String sql) throws SQLException {
        var rows = new ArrayList<List<String>>();

        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            var count = resultSet.getMetaData().getColumnCount();

            while (resultSet.next()) {
                var row = new ArrayList<String>();

                for (var i = 1; i <= count; i++) {
                    var value = resultSet.getString(i);

                    row.add(value == null ? "NULL" : value);
                }

                rows.add(row);
            }
        }

        return rows;
    }
}
