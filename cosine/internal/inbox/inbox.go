// Package inbox watches the Inbox folder (§4.1): anything dropped there is
// ingested within seconds, via filesystem events rather than scans.
package inbox

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"

	"github.com/lososdavidos/Co-sine-/cosine/internal/ingest"
	"github.com/lososdavidos/Co-sine-/cosine/internal/store"
)

// FailedDir holds files that could not be ingested, so they are not retried forever.
const FailedDir = "_failed"

type IngestFunc func(ctx context.Context, path string) error

type Watcher struct {
	Dir    string
	Ingest IngestFunc
	// Settle is how long a file's size must stay unchanged before it is
	// ingested. A file copied over SMB or SFTP fires events long before it
	// is complete; hashing it early would store a hash for a file that no
	// longer exists a second later.
	Settle time.Duration
	Log    *slog.Logger
}

type pending struct {
	size     int64
	stableAt time.Time
}

// Run watches until ctx is cancelled. Files already present are picked up at start.
func (w *Watcher) Run(ctx context.Context) error {
	if err := os.MkdirAll(w.Dir, 0o755); err != nil {
		return err
	}
	fw, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	defer fw.Close()

	queue := map[string]*pending{}
	w.addTree(fw, w.Dir, queue)

	tick := time.NewTicker(w.Settle / 2)
	defer tick.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case ev, ok := <-fw.Events:
			if !ok {
				return nil
			}
			if ev.Op&(fsnotify.Create|fsnotify.Write|fsnotify.Rename) == 0 {
				continue
			}
			if fi, err := os.Stat(ev.Name); err == nil && fi.IsDir() {
				w.addTree(fw, ev.Name, queue)
			} else if err == nil {
				w.track(ev.Name, queue)
			}
		case err, ok := <-fw.Errors:
			if !ok {
				return nil
			}
			w.Log.Warn("inbox watcher", "err", err)
		case now := <-tick.C:
			w.settle(ctx, now, queue)
		}
	}
}

// addTree watches dir and every directory below it (fsnotify is not
// recursive), queueing any files already there.
func (w *Watcher) addTree(fw *fsnotify.Watcher, dir string, queue map[string]*pending) {
	filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if d.IsDir() {
			if d.Name() == FailedDir || (p != w.Dir && ignored(d.Name())) {
				return filepath.SkipDir
			}
			if err := fw.Add(p); err != nil {
				w.Log.Warn("cannot watch", "dir", p, "err", err)
			}
			return nil
		}
		w.track(p, queue)
		return nil
	})
}

func (w *Watcher) track(path string, queue map[string]*pending) {
	if ignored(filepath.Base(path)) || strings.Contains(path, string(filepath.Separator)+FailedDir+string(filepath.Separator)) {
		return
	}
	if _, ok := store.ContentType(filepath.Ext(path)); !ok {
		return // not audio: left alone
	}
	if _, ok := queue[path]; !ok {
		queue[path] = &pending{size: -1}
	}
}

func (w *Watcher) settle(ctx context.Context, now time.Time, queue map[string]*pending) {
	for path, p := range queue {
		fi, err := os.Stat(path)
		if err != nil {
			delete(queue, path) // gone before it settled
			continue
		}
		if fi.Size() != p.size {
			p.size, p.stableAt = fi.Size(), now
			continue
		}
		if now.Sub(p.stableAt) < w.Settle || now.Sub(fi.ModTime()) < w.Settle {
			continue
		}
		delete(queue, path)
		w.process(ctx, path)
	}
}

func (w *Watcher) process(ctx context.Context, path string) {
	err := w.Ingest(ctx, path)
	switch {
	case err == nil:
		w.Log.Info("ingested", "file", path)
		w.pruneUp(filepath.Dir(path))
	case errors.Is(err, ingest.ErrNotAudio):
	case errors.Is(err, ingest.ErrNoStore):
		// Nothing to file into yet: leave it for when the Store is configured.
		w.Log.Warn("inbox file waiting for a Store path", "file", path)
	default:
		w.Log.Error("ingest failed", "file", path, "err", err)
		failed := filepath.Join(w.Dir, FailedDir)
		os.MkdirAll(failed, 0o755)
		os.Rename(path, filepath.Join(failed, filepath.Base(path)))
	}
}

// pruneUp removes folders emptied by ingest, up to (not including) the Inbox.
// Only folders Cosine emptied: a folder someone just created and is about to
// copy into must survive.
func (w *Watcher) pruneUp(dir string) {
	for dir != w.Dir && strings.HasPrefix(dir, w.Dir) {
		if os.Remove(dir) != nil {
			return
		}
		dir = filepath.Dir(dir)
	}
}

// ignored skips hidden files and the temporary names copy tools write.
func ignored(name string) bool {
	if strings.HasPrefix(name, ".") || strings.HasPrefix(name, "~") {
		return true
	}
	for _, s := range []string{".part", ".partial", ".tmp", ".crdownload", ".filepart"} {
		if strings.HasSuffix(strings.ToLower(name), s) {
			return true
		}
	}
	return false
}
