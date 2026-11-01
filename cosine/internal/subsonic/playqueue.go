package subsonic

import (
	"errors"
	"strings"

	"github.com/lososdavidos/Co-sine-/cosine/internal/devices"
)

// PlayQueue is a Subsonic app's queue as saved: the songs, the one playing
// and how far into it, and which app saved it.
type PlayQueue struct {
	Current   string  `xml:"current,attr,omitempty" json:"current,omitempty"`
	Position  int64   `xml:"position,attr" json:"position"`
	Username  string  `xml:"username,attr" json:"username"`
	Changed   string  `xml:"changed,attr" json:"changed"`
	ChangedBy string  `xml:"changedBy,attr" json:"changedBy"`
	Entry     []Child `xml:"entry" json:"entry"`
}

// clientDevice: a Subsonic app is a Device named by its client parameter.
func clientDevice(r *request) devices.Device {
	c := strings.TrimSpace(r.FormValue("c"))
	if c == "" {
		c = "Subsonic app"
	}
	if len(c) > 80 {
		c = c[:80]
	}
	return devices.Device{ID: "subsonic:" + c, Name: c, Kind: "subsonic"}
}

// savePlayQueue keeps a Subsonic app's queue as its Device's session
// (§4A), where Sine and the other apps can pick it up.
func savePlayQueue(r *request) (*Response, error) {
	if r.api.Devices == nil {
		return nil, apiError{ErrNotFound, "Play queues are not kept here."}
	}
	r.ParseForm()
	sess := devices.Session{Queue: r.Form["id"], PositionMs: int64(r.intParam("position", 0, 1<<62))}
	if cur := r.FormValue("current"); cur != "" {
		for i, id := range sess.Queue {
			if id == cur {
				sess.Current = i
				break
			}
		}
	}
	if err := r.api.Devices.Save(r.ctx(), r.user.ID, clientDevice(r), sess); err != nil {
		return nil, err
	}
	return &Response{}, nil
}

// getPlayQueue answers with the account's most recently changed session,
// from whichever app or phone: so playback picks up where it was left.
func getPlayQueue(r *request) (*Response, error) {
	if r.api.Devices == nil {
		return &Response{}, nil
	}
	sess, d, err := r.api.Devices.Latest(r.ctx(), r.user.ID)
	if errors.Is(err, devices.ErrNotFound) {
		return &Response{}, nil
	}
	if err != nil {
		return nil, err
	}
	var ids []int64
	for _, t := range sess.Queue {
		if id, ok := parseID(t, "tr-"); ok {
			ids = append(ids, id)
		}
	}
	songs, err := r.lib.songsInOrder(r.ctx(), r.user.ID, ids)
	if err != nil {
		return nil, err
	}
	q := &PlayQueue{Position: sess.PositionMs, Username: r.user.Name, Changed: sess.UpdatedAt.UTC().Format("2006-01-02T15:04:05.000Z"), ChangedBy: d.Name, Entry: songs}
	if sess.Current >= 0 && sess.Current < len(sess.Queue) {
		q.Current = sess.Queue[sess.Current]
	}
	return &Response{PlayQueue: q}, nil
}
