package resolve

import (
	"regexp"
	"strings"
	"unicode"
)

// What uploaders add to a title that isn't part of it. A bracketed group
// made only of these words is dropped: "[HD Upgrade]", "(Official Music
// Video)", "[Free DL]", "(Out Now!)". A group with any other word in it is
// kept, because "(VIP)", "(Extended Mix)" and "(feat. X)" are the title.
var noiseWords = map[string]bool{
	"official": true, "music": true, "video": true, "audio": true, "lyric": true, "lyrics": true,
	"visualizer": true, "visualiser": true, "visualization": true, "visualisation": true,
	"hd": true, "hq": true, "4k": true, "8k": true, "1080p": true, "720p": true, "480p": true, "60fps": true,
	"upgrade": true, "upgraded": true, "high": true, "quality": true, "version": true,
	"free": true, "download": true, "dl": true, "out": true, "now": true,
	"premiere": true, "exclusive": true, "full": true, "song": true, "explicit": true,
	"clip": true, "mv": true, "m": true, "v": true, "only": true, "in": true, "description": true,
}

var (
	// [Monstercat Release], [Electronic Gems Premiere]: a label or channel
	// announcing itself. Short groups only, so a real title isn't eaten.
	announcement = regexp.MustCompile(`(?i)^(?:[\p{L}\p{N}.&' ]{1,40} )?(?:release|premiere|exclusive)$`)
	// "free download" or "out now" anywhere in a group marks it as an advert:
	// "[FREE DOWNLOAD IN DESC]", "(OUT NOW ON SOME LABEL)".
	advert   = regexp.MustCompile(`(?i)\bfree\s*(?:download|dl)\b|\bout\s+now\b`)
	brackets = regexp.MustCompile(`\s*(?:\[([^\[\]]*)\]|\(([^()]*)\)|【([^【】]*)】|（([^（）]*)）|\{([^{}]*)\}|\*([^*]+)\*)`)
	// "PREMIERE: …", "FREE DL | …", "EXCLUSIVE - …" before the real title.
	prefix = regexp.MustCompile(`(?i)^\s*(?:premiere|exclusive|free\s*(?:download|dl)|out\s+now|new)\s*[:|]\s*`)
	// The same adverts unbracketed at the end: "Tides FREE DOWNLOAD", "Tides - HD".
	suffix   = regexp.MustCompile(`(?i)[\s\-–—|:!]*\b(?:free\s*(?:download|dl)|out\s+now|hd|hq|4k)\s*!*$`)
	hashtags = regexp.MustCompile(`(?:\s+#[\p{L}\p{N}_]+)+$`)
	spaces   = regexp.MustCompile(`\s+`)
	year     = regexp.MustCompile(`^(?:19|20)\d\d$`)
	// YouTube's generated channels: "Skeler - Topic", "SkelerVEVO".
	topic = regexp.MustCompile(`(?i)\s+-\s+topic$`)
	vevo  = regexp.MustCompile(`([\p{Ll}\p{N}])VEVO$`)
)

// CleanTitle strips the noise uploaders add to titles, keeping everything
// that names the actual piece. It is idempotent.
func CleanTitle(s string) string {
	s = spaces.ReplaceAllString(strings.TrimSpace(s), " ")
	orig := s
	for {
		t := prefix.ReplaceAllString(s, "")
		if t == s {
			break
		}
		s = t
	}
	// "Artist - Title | Label" or "… // Free Download": once there is an
	// artist and a title, what follows a bar is about the upload. Without
	// a dash the bar may be the only separator, so it stays.
	for _, bar := range []string{" | ", " // ", " || "} {
		if i := strings.Index(s, bar); i > 0 {
			head, tail := s[:i], s[i+len(bar):]
			if hasDash(head) || isNoise(tail) {
				s = head
			}
		}
	}
	s = brackets.ReplaceAllStringFunc(s, func(m string) string {
		inner := brackets.FindStringSubmatch(m)
		content := ""
		for _, g := range inner[1:] {
			content += g
		}
		if isNoise(content) {
			return ""
		}
		return m
	})
	s = hashtags.ReplaceAllString(s, "")
	for {
		t := suffix.ReplaceAllString(s, "")
		if t == s {
			break
		}
		s = t
	}
	s = spaces.ReplaceAllString(s, " ")
	s = strings.TrimFunc(s, func(r rune) bool { return unicode.IsSpace(r) || strings.ContainsRune("-–—|:", r) })
	if s == "" {
		return orig // a title that is all "noise" ("HD", "Free Download") is still its title
	}
	return s
}

// CleanArtist is CleanTitle for names, plus YouTube's generated-channel suffixes.
func CleanArtist(s string) string {
	s = topic.ReplaceAllString(strings.TrimSpace(s), "")
	s = vevo.ReplaceAllString(s, "$1")
	return CleanTitle(s)
}

func hasDash(s string) bool {
	for _, sep := range []string{" - ", " – ", " — "} {
		if strings.Contains(s, sep) {
			return true
		}
	}
	return false
}

// isNoise reports whether a bracketed group says nothing about the piece.
func isNoise(content string) bool {
	content = strings.TrimSpace(content)
	if content == "" {
		return true
	}
	if advert.MatchString(content) || announcement.MatchString(content) {
		return true
	}
	words := strings.FieldsFunc(strings.ToLower(content), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsNumber(r)
	})
	if len(words) == 0 {
		return true // only punctuation: "(!!)"
	}
	noisy := false
	for _, w := range words {
		switch {
		case noiseWords[w]:
			noisy = true
		case year.MatchString(w):
			// "[HD 2019]" is noise, "(1998)" alone is not.
		default:
			return false
		}
	}
	return noisy
}
