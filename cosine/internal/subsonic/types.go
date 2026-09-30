package subsonic

import "encoding/xml"

// Wire types for the Subsonic API. One set of structs serves both JSON and
// XML: DSub and older clients still speak XML.

const (
	APIVersion = "1.16.1"
	ServerType = "cosine"
)

type Response struct {
	XMLName       xml.Name `xml:"http://subsonic.org/restapi subsonic-response" json:"-"`
	Status        string   `xml:"status,attr" json:"status"`
	Version       string   `xml:"version,attr" json:"version"`
	Type          string   `xml:"type,attr" json:"type"`
	ServerVersion string   `xml:"serverVersion,attr" json:"serverVersion"`
	OpenSubsonic  bool     `xml:"openSubsonic,attr" json:"openSubsonic"`

	Error         *Error         `xml:"error,omitempty" json:"error,omitempty"`
	License       *License       `xml:"license,omitempty" json:"license,omitempty"`
	MusicFolders  *MusicFolders  `xml:"musicFolders,omitempty" json:"musicFolders,omitempty"`
	Artists       *Artists       `xml:"artists,omitempty" json:"artists,omitempty"`
	Artist        *Artist        `xml:"artist,omitempty" json:"artist,omitempty"`
	AlbumList2    *AlbumList     `xml:"albumList2,omitempty" json:"albumList2,omitempty"`
	Album         *Album         `xml:"album,omitempty" json:"album,omitempty"`
	Song          *Child         `xml:"song,omitempty" json:"song,omitempty"`
	RandomSongs   *Songs         `xml:"randomSongs,omitempty" json:"randomSongs,omitempty"`
	Starred2      *SearchResult3 `xml:"starred2,omitempty" json:"starred2,omitempty"`
	SearchResult3 *SearchResult3 `xml:"searchResult3,omitempty" json:"searchResult3,omitempty"`
	Playlists     *Playlists     `xml:"playlists,omitempty" json:"playlists,omitempty"`
	Playlist      *Playlist      `xml:"playlist,omitempty" json:"playlist,omitempty"`
	User          *User          `xml:"user,omitempty" json:"user,omitempty"`
}

type Error struct {
	Code    int    `xml:"code,attr" json:"code"`
	Message string `xml:"message,attr" json:"message"`
}

// Subsonic error codes.
const (
	ErrGeneric       = 0
	ErrMissingParam  = 10
	ErrWrongCreds    = 40
	ErrNotAuthorized = 50
	ErrNotFound      = 70
)

type License struct {
	Valid bool `xml:"valid,attr" json:"valid"`
}

type MusicFolders struct {
	MusicFolder []MusicFolder `xml:"musicFolder" json:"musicFolder"`
}

type MusicFolder struct {
	ID   int    `xml:"id,attr" json:"id"`
	Name string `xml:"name,attr" json:"name"`
}

type Artists struct {
	IgnoredArticles string  `xml:"ignoredArticles,attr" json:"ignoredArticles"`
	Index           []Index `xml:"index" json:"index"`
}

type Index struct {
	Name   string   `xml:"name,attr" json:"name"`
	Artist []Artist `xml:"artist" json:"artist"`
}

type Artist struct {
	ID         string  `xml:"id,attr" json:"id"`
	Name       string  `xml:"name,attr" json:"name"`
	CoverArt   string  `xml:"coverArt,attr,omitempty" json:"coverArt,omitempty"`
	AlbumCount int     `xml:"albumCount,attr" json:"albumCount"`
	Album      []Album `xml:"album,omitempty" json:"album,omitempty"`
}

type AlbumList struct {
	Album []Album `xml:"album" json:"album"`
}

type Album struct {
	ID        string  `xml:"id,attr" json:"id"`
	Name      string  `xml:"name,attr" json:"name"`
	Artist    string  `xml:"artist,attr" json:"artist"`
	ArtistID  string  `xml:"artistId,attr" json:"artistId"`
	CoverArt  string  `xml:"coverArt,attr,omitempty" json:"coverArt,omitempty"`
	SongCount int     `xml:"songCount,attr" json:"songCount"`
	Duration  int     `xml:"duration,attr" json:"duration"`
	Year      int     `xml:"year,attr,omitempty" json:"year,omitempty"`
	Created   string  `xml:"created,attr" json:"created"`
	Song      []Child `xml:"song,omitempty" json:"song,omitempty"`
}

type Child struct {
	ID          string `xml:"id,attr" json:"id"`
	Parent      string `xml:"parent,attr" json:"parent"`
	IsDir       bool   `xml:"isDir,attr" json:"isDir"`
	Title       string `xml:"title,attr" json:"title"`
	Album       string `xml:"album,attr" json:"album"`
	Artist      string `xml:"artist,attr" json:"artist"`
	Track       int    `xml:"track,attr,omitempty" json:"track,omitempty"`
	DiscNumber  int    `xml:"discNumber,attr,omitempty" json:"discNumber,omitempty"`
	Year        int    `xml:"year,attr,omitempty" json:"year,omitempty"`
	CoverArt    string `xml:"coverArt,attr,omitempty" json:"coverArt,omitempty"`
	Size        int64  `xml:"size,attr" json:"size"`
	ContentType string `xml:"contentType,attr" json:"contentType"`
	Suffix      string `xml:"suffix,attr" json:"suffix"`
	Duration    int    `xml:"duration,attr" json:"duration"`
	BitRate     int    `xml:"bitRate,attr,omitempty" json:"bitRate,omitempty"`
	Path        string `xml:"path,attr" json:"path"`
	AlbumID     string `xml:"albumId,attr" json:"albumId"`
	ArtistID    string `xml:"artistId,attr" json:"artistId"`
	Type        string `xml:"type,attr" json:"type"`
	Created     string `xml:"created,attr" json:"created"`
	Starred     string `xml:"starred,attr,omitempty" json:"starred,omitempty"`
}

type Songs struct {
	Song []Child `xml:"song" json:"song"`
}

type SearchResult3 struct {
	Artist []Artist `xml:"artist" json:"artist"`
	Album  []Album  `xml:"album" json:"album"`
	Song   []Child  `xml:"song" json:"song"`
}

type Playlists struct {
	Playlist []Playlist `xml:"playlist" json:"playlist"`
}

type Playlist struct {
	ID        string  `xml:"id,attr" json:"id"`
	Name      string  `xml:"name,attr" json:"name"`
	Owner     string  `xml:"owner,attr" json:"owner"`
	Public    bool    `xml:"public,attr" json:"public"`
	SongCount int     `xml:"songCount,attr" json:"songCount"`
	Duration  int     `xml:"duration,attr" json:"duration"`
	Created   string  `xml:"created,attr" json:"created"`
	Changed   string  `xml:"changed,attr" json:"changed"`
	Entry     []Child `xml:"entry,omitempty" json:"entry,omitempty"`
}

type User struct {
	Username          string `xml:"username,attr" json:"username"`
	AdminRole         bool   `xml:"adminRole,attr" json:"adminRole"`
	StreamRole        bool   `xml:"streamRole,attr" json:"streamRole"`
	DownloadRole      bool   `xml:"downloadRole,attr" json:"downloadRole"`
	PlaylistRole      bool   `xml:"playlistRole,attr" json:"playlistRole"`
	CoverArtRole      bool   `xml:"coverArtRole,attr" json:"coverArtRole"`
	ScrobblingEnabled bool   `xml:"scrobblingEnabled,attr" json:"scrobblingEnabled"`
}
