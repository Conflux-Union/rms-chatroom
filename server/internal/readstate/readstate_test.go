package readstate

import (
	"testing"

	"github.com/DATA-DOG/go-sqlmock"
)

// Query patterns match by regex; \s+ absorbs the implementation's multiline
// indentation so the tests don't depend on exact whitespace.
const selectLastRead = `SELECT last_read_message_id FROM read_positions WHERE user_id = \? AND channel_id = \?`

const selectUnreadCount = `SELECT COUNT\(\*\) FROM messages\s+WHERE channel_id = \? AND id > \? AND user_id != \? AND is_deleted = FALSE`

const selectLastMention = `SELECT MAX\(m\.id\) FROM message_mentions mm\s+JOIN messages m ON m\.id = mm\.message_id\s+WHERE mm\.user_id = \? AND m\.channel_id = \? AND m\.id > \? AND m\.is_deleted = FALSE`

const upsertAdvance = `INSERT INTO read_positions \(user_id, channel_id, last_read_message_id\)\s+VALUES \(\?, \?, \?\)\s+ON DUPLICATE KEY UPDATE\s+last_read_message_id = GREATEST\(last_read_message_id, VALUES\(last_read_message_id\)\),\s+updated_at = UTC_TIMESTAMP\(\)`

const updateMentionCols = `UPDATE read_positions SET has_mention = \?, last_mention_message_id = \?, updated_at = UTC_TIMESTAMP\(\)\s+WHERE user_id = \? AND channel_id = \?`

const insertSeed = `INSERT IGNORE INTO read_positions \(user_id, channel_id, last_read_message_id\)\s+VALUES \(\?, \?, \?\)`

func TestGetChannelStateNoRow(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(selectLastRead).
		WithArgs(int64(42), int64(1)).
		WillReturnRows(sqlmock.NewRows([]string{"last_read_message_id"}))

	st, err := GetChannelState(db, 42, 1)
	if err != nil {
		t.Fatalf("GetChannelState: %v", err)
	}
	if st.Exists {
		t.Error("Exists should be false when no row exists")
	}
	if st.LastReadMessageID != 0 || st.UnreadCount != 0 || st.HasMention {
		t.Errorf("expected zero state, got %+v", st)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Errorf("expectations: %v", err)
	}
}

func TestGetChannelStateDerivesCountAndMention(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(selectLastRead).
		WithArgs(int64(42), int64(1)).
		WillReturnRows(sqlmock.NewRows([]string{"last_read_message_id"}).AddRow(100))
	mock.ExpectQuery(selectUnreadCount).
		WithArgs(int64(1), int64(100), int64(42)).
		WillReturnRows(sqlmock.NewRows([]string{"c"}).AddRow(3))
	mock.ExpectQuery(selectLastMention).
		WithArgs(int64(42), int64(1), int64(100)).
		WillReturnRows(sqlmock.NewRows([]string{"m"}).AddRow(120))

	st, err := GetChannelState(db, 42, 1)
	if err != nil {
		t.Fatalf("GetChannelState: %v", err)
	}
	if !st.Exists || st.LastReadMessageID != 100 || st.UnreadCount != 3 {
		t.Errorf("unexpected state: %+v", st)
	}
	if !st.HasMention || st.LastMentionMessageID == nil || *st.LastMentionMessageID != 120 {
		t.Errorf("mention state wrong: %+v", st)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Errorf("expectations: %v", err)
	}
}

func TestDeriveFromPositionNoMention(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	defer db.Close()

	mock.ExpectQuery(selectUnreadCount).
		WillReturnRows(sqlmock.NewRows([]string{"c"}).AddRow(0))
	mock.ExpectQuery(selectLastMention).
		WillReturnRows(sqlmock.NewRows([]string{"m"}).AddRow(nil))

	st, err := DeriveFromPosition(db, 42, 1, 500)
	if err != nil {
		t.Fatalf("DeriveFromPosition: %v", err)
	}
	if st.UnreadCount != 0 || st.HasMention || st.LastMentionMessageID != nil {
		t.Errorf("expected clean state, got %+v", st)
	}
	if st.LastReadMessageID != 500 || !st.Exists {
		t.Errorf("position not echoed: %+v", st)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Errorf("expectations: %v", err)
	}
}

func TestSeedIfMissing(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	defer db.Close()

	mock.ExpectExec(insertSeed).
		WithArgs(int64(42), int64(1), int64(999)).
		WillReturnResult(sqlmock.NewResult(0, 1))

	if err := SeedIfMissing(db, 42, 1, 999); err != nil {
		t.Fatalf("SeedIfMissing: %v", err)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Errorf("expectations: %v", err)
	}
}

func TestAdvanceReadPosition(t *testing.T) {
	db, mock, err := sqlmock.New()
	if err != nil {
		t.Fatalf("sqlmock.New: %v", err)
	}
	defer db.Close()

	// The stored position after GREATEST may exceed the request when another
	// device was already ahead; the derived state must reflect the stored one.
	mock.ExpectExec(upsertAdvance).
		WithArgs(int64(42), int64(1), int64(150)).
		WillReturnResult(sqlmock.NewResult(0, 1))
	mock.ExpectQuery(selectLastRead).
		WithArgs(int64(42), int64(1)).
		WillReturnRows(sqlmock.NewRows([]string{"last_read_message_id"}).AddRow(200))
	mock.ExpectQuery(selectUnreadCount).
		WithArgs(int64(1), int64(200), int64(42)).
		WillReturnRows(sqlmock.NewRows([]string{"c"}).AddRow(1))
	mock.ExpectQuery(selectLastMention).
		WithArgs(int64(42), int64(1), int64(200)).
		WillReturnRows(sqlmock.NewRows([]string{"m"}).AddRow(nil))
	mock.ExpectExec(updateMentionCols).
		WithArgs(false, sqlmock.AnyArg(), int64(42), int64(1)).
		WillReturnResult(sqlmock.NewResult(0, 1))

	st, err := AdvanceReadPosition(db, 42, 1, 150)
	if err != nil {
		t.Fatalf("AdvanceReadPosition: %v", err)
	}
	if st.LastReadMessageID != 200 || st.UnreadCount != 1 || st.HasMention {
		t.Errorf("unexpected state: %+v", st)
	}
	if err := mock.ExpectationsWereMet(); err != nil {
		t.Errorf("expectations: %v", err)
	}
}
