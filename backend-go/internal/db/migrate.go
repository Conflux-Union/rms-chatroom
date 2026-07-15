// Package db runs the embedded golang-migrate migrations at startup so
// deploys (including self-updates) never need a manual migration step.
package db

import (
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"log"
	"regexp"
	"strconv"
	"strings"

	"github.com/golang-migrate/migrate/v4"
	migratemysql "github.com/golang-migrate/migrate/v4/database/mysql"
	"github.com/golang-migrate/migrate/v4/source/iofs"
)

//go:embed migrations/*.sql
var migrationsFS embed.FS

// Migrate applies all pending .up.sql migrations. dsn is the already
// normalized mysql DSN used by the server; a dedicated connection with
// multiStatements enabled is opened because migration files contain multiple
// statements.
//
// Databases that predate the migration runner (tables exist but no
// schema_migrations) are baselined to the newest embedded version instead of
// re-running DDL that would collide with the existing schema.
func Migrate(dsn string) error {
	if !strings.Contains(dsn, "multiStatements=") {
		if strings.Contains(dsn, "?") {
			dsn += "&multiStatements=true"
		} else {
			dsn += "?multiStatements=true"
		}
	}

	sqlDB, err := sql.Open("mysql", dsn)
	if err != nil {
		return fmt.Errorf("migrate: open database: %w", err)
	}
	defer sqlDB.Close()

	baseline, err := needsBaseline(sqlDB)
	if err != nil {
		return fmt.Errorf("migrate: baseline check: %w", err)
	}

	driver, err := migratemysql.WithInstance(sqlDB, &migratemysql.Config{})
	if err != nil {
		return fmt.Errorf("migrate: init driver: %w", err)
	}
	src, err := iofs.New(migrationsFS, "migrations")
	if err != nil {
		return fmt.Errorf("migrate: load embedded migrations: %w", err)
	}
	m, err := migrate.NewWithInstance("iofs", src, "mysql", driver)
	if err != nil {
		return fmt.Errorf("migrate: init: %w", err)
	}

	if baseline {
		latest, err := latestMigrationVersion(migrationsFS)
		if err != nil {
			return fmt.Errorf("migrate: %w", err)
		}
		log.Printf("migrate: existing schema without schema_migrations detected; baselining to version %d without running migrations", latest)
		if err := m.Force(latest); err != nil {
			return fmt.Errorf("migrate: baseline force: %w", err)
		}
	}

	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("migrate: apply: %w (if the database is marked dirty, fix the schema manually and reset the version in schema_migrations)", err)
	}

	v, dirty, err := m.Version()
	if err != nil && !errors.Is(err, migrate.ErrNilVersion) {
		return fmt.Errorf("migrate: read version: %w", err)
	}
	log.Printf("migrate: schema at version %d (dirty=%v)", v, dirty)
	return nil
}

// needsBaseline reports whether the database has an existing schema that was
// created before the migration runner was introduced.
func needsBaseline(db *sql.DB) (bool, error) {
	hasMigrations, err := tableExists(db, "schema_migrations")
	if err != nil {
		return false, err
	}
	if hasMigrations {
		return false, nil
	}
	// servers is created by 000001_initial and is the most reliable marker
	// for a pre-runner schema (there is no users table — identity lives in
	// SSO, not this database).
	hasServers, err := tableExists(db, "servers")
	if err != nil {
		return false, err
	}
	return hasServers, nil
}

func tableExists(db *sql.DB, name string) (bool, error) {
	var n int
	err := db.QueryRow(
		"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?", name,
	).Scan(&n)
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

var migrationFilePattern = regexp.MustCompile(`^(\d+)_.+\.up\.sql$`)

// latestMigrationVersion returns the highest version number among the
// embedded .up.sql files.
func latestMigrationVersion(fsys embed.FS) (int, error) {
	entries, err := fsys.ReadDir("migrations")
	if err != nil {
		return 0, err
	}
	latest := 0
	for _, e := range entries {
		match := migrationFilePattern.FindStringSubmatch(e.Name())
		if match == nil {
			continue
		}
		v, err := strconv.Atoi(match[1])
		if err != nil {
			continue
		}
		if v > latest {
			latest = v
		}
	}
	if latest == 0 {
		return 0, errors.New("no embedded migrations found")
	}
	return latest, nil
}
