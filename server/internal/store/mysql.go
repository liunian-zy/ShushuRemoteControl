package store

import (
	"database/sql"
	"errors"
	"time"

	_ "github.com/go-sql-driver/mysql"

	"shushu-remote-control/internal/model"
)

var (
	ErrInvalidToken   = errors.New("invalid token")
	ErrTokenExpired   = errors.New("token expired")
	ErrDeviceNotFound = errors.New("device not found")
)

// DeviceStore handles device persistence in MySQL.
type DeviceStore struct {
	db *sql.DB
}

// NewDeviceStore creates a MySQL-backed device store.
func NewDeviceStore(dsn string) (*DeviceStore, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	return &DeviceStore{db: db}, nil
}

func (s *DeviceStore) Close() error {
	if s == nil || s.db == nil {
		return nil
	}
	return s.db.Close()
}

// UpsertDevice inserts or updates device info without touching control tokens.
func (s *DeviceStore) UpsertDevice(device *model.Device) error {
	if s == nil || s.db == nil || device == nil {
		return nil
	}
	const query = `
INSERT INTO rc_devices (id, name, screen_width, screen_height, online, last_seen)
VALUES (?, ?, ?, ?, ?, NOW())
ON DUPLICATE KEY UPDATE
  name = VALUES(name),
  screen_width = VALUES(screen_width),
  screen_height = VALUES(screen_height),
  online = VALUES(online),
  last_seen = NOW()
`
	_, err := s.db.Exec(query, device.ID, device.Name, device.ScreenWidth, device.ScreenHeight, device.Online)
	return err
}

// SyncExternalDeviceID updates external devices table mapping when device reports its ID.
func (s *DeviceStore) SyncExternalDeviceID(deviceID string) error {
	if s == nil || s.db == nil || deviceID == "" {
		return nil
	}
	_, err := s.db.Exec(`UPDATE devices SET rc_device_id = ? WHERE device_no = ?`, deviceID, deviceID)
	return err
}

// ValidateControlToken validates device token for controller access.
func (s *DeviceStore) ValidateControlToken(deviceID, token string) (*model.Device, error) {
	if s == nil || s.db == nil {
		return nil, errors.New("store not initialized")
	}

	const query = `
SELECT id, name, alias, screen_width, screen_height, token, token_expires, online
FROM rc_devices
WHERE id = ?
`
	var (
		id           string
		name         string
		alias        string
		screenWidth  int
		screenHeight int
		dbToken      string
		tokenExpires sql.NullTime
		online       bool
	)

	if err := s.db.QueryRow(query, deviceID).Scan(
		&id,
		&name,
		&alias,
		&screenWidth,
		&screenHeight,
		&dbToken,
		&tokenExpires,
		&online,
	); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrDeviceNotFound
		}
		return nil, err
	}

	if token == "" || dbToken == "" || token != dbToken {
		return nil, ErrInvalidToken
	}

	if tokenExpires.Valid && tokenExpires.Time.Before(time.Now()) {
		return nil, ErrTokenExpired
	}

	displayName := name
	if alias != "" {
		displayName = alias
	}

	return &model.Device{
		ID:           id,
		Name:         displayName,
		ScreenWidth:  screenWidth,
		ScreenHeight: screenHeight,
		Online:       online,
	}, nil
}

// SetOnline updates device online status and last seen timestamp.
func (s *DeviceStore) SetOnline(deviceID string, online bool) error {
	if s == nil || s.db == nil {
		return nil
	}
	_, err := s.db.Exec(`UPDATE rc_devices SET online = ?, last_seen = NOW() WHERE id = ?`, online, deviceID)
	return err
}

// UpdateDeviceInfo updates device metadata without touching control tokens.
func (s *DeviceStore) UpdateDeviceInfo(device *model.Device) error {
	if s == nil || s.db == nil || device == nil {
		return nil
	}
	_, err := s.db.Exec(
		`UPDATE rc_devices SET name = ?, screen_width = ?, screen_height = ?, last_seen = NOW() WHERE id = ?`,
		device.Name,
		device.ScreenWidth,
		device.ScreenHeight,
		device.ID,
	)
	return err
}
