# H2 2.4.240 compatibility fixture

`legacy-2.4.240.mv.db` was created and cleanly closed with the unmodified H2 2.4.240 driver, using the production `schema.sql`, `platform-schema.sql`, then `legacy-seed.sql`, all on the same JDBC connection. All rows and identifiers are synthetic. It contains no user data or working credentials.

`H2PoolRetirementCheckTest` copies the file into a temporary directory before opening it with the pinned current driver. It reapplies startup DDL, preserves the original feedback/report/share rows, evicts the schema-loading Hikari physical connection while a second connection holds the file open, and checks new valid writes plus rejection of invalid enum values. The original resource file is never opened in place.

To recreate it, run `org.h2.tools.RunScript` from H2 2.4.240 with a JDBC URL `jdbc:h2:file:<temporary-base>;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`, user `sa`, empty password, and a script containing those three SQL files in that order. Copy the closed `.mv.db` file here. Do not open the source fixture with a newer driver during regeneration.
