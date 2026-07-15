package db

import (
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

func TestLatestMigrationVersion(t *testing.T) {
	v, err := latestMigrationVersion(migrationsFS)
	if err != nil {
		t.Fatalf("latestMigrationVersion: %v", err)
	}
	if v < 7 {
		t.Errorf("latest version = %d, want >= 7 (did embedded migrations go missing?)", v)
	}
}

const tableExistsQuery = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?"

func TestNeedsBaseline(t *testing.T) {
	cases := []struct {
		name             string
		hasMigrations    bool
		hasServers       bool
		wantBaseline     bool
		serversQueryRuns bool
	}{
		{"tracked schema", true, true, false, false},
		{"legacy schema without tracking", false, true, true, true},
		{"fresh database", false, false, false, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			mockDB, mock, err := sqlmock.New(sqlmock.QueryMatcherOption(sqlmock.QueryMatcherEqual))
			if err != nil {
				t.Fatal(err)
			}
			defer mockDB.Close()

			count := func(b bool) int {
				if b {
					return 1
				}
				return 0
			}
			mock.ExpectQuery(tableExistsQuery).WithArgs("schema_migrations").
				WillReturnRows(sqlmock.NewRows([]string{"COUNT(*)"}).AddRow(count(tc.hasMigrations)))
			if tc.serversQueryRuns {
				mock.ExpectQuery(tableExistsQuery).WithArgs("servers").
					WillReturnRows(sqlmock.NewRows([]string{"COUNT(*)"}).AddRow(count(tc.hasServers)))
			}

			got, err := needsBaseline(mockDB)
			if err != nil {
				t.Fatalf("needsBaseline: %v", err)
			}
			if got != tc.wantBaseline {
				t.Errorf("needsBaseline = %v, want %v", got, tc.wantBaseline)
			}
			if err := mock.ExpectationsWereMet(); err != nil {
				t.Errorf("unmet expectations: %v", err)
			}
		})
	}
}
