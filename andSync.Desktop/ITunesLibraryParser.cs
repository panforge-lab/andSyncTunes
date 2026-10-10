using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Xml.Linq;

namespace andSync.Desktop;

public record Track(
    int TrackId,
    string Name,
    string? Artist,
    string? Album,
    string? Location,
    long Size = 0
);

public record Playlist(
    int PlaylistId,
    string Name,
    List<int> TrackIds,
    string? PersistentId = null,
    string? ParentPersistentId = null,
    bool IsFolder = false,
    int? DistinguishedKind = null
);

public class ITunesLibrary
{
    public Dictionary<int, Track> Tracks { get; } = new();
    public List<Playlist> Playlists { get; } = new();
}

public static class ITunesLibraryParser
{
    public static ITunesLibrary Parse(string xmlFilePath)
    {
        if (!File.Exists(xmlFilePath))
        {
            throw new FileNotFoundException("iTunes Library XML file not found.", xmlFilePath);
        }

        var library = new ITunesLibrary();

        var doc = XDocument.Load(xmlFilePath);
        var rootDict = doc.Root?.Element("dict");
        if (rootDict == null) return library;

        var rootElements = rootDict.Elements().ToList();

        for (int i = 0; i < rootElements.Count - 1; i++)
        {
            var elem = rootElements[i];
            if (elem.Name.LocalName == "key")
            {
                string key = elem.Value;
                var valElem = rootElements[i + 1];

                if (key == "Tracks" && valElem.Name.LocalName == "dict")
                {
                    ParseTracksDict(valElem, library);
                }
                else if (key == "Playlists" && valElem.Name.LocalName == "array")
                {
                    ParsePlaylistsArray(valElem, library);
                }
            }
        }

        return library;
    }

    private static void ParseTracksDict(XElement tracksDict, ITunesLibrary library)
    {
        var elements = tracksDict.Elements().ToList();
        for (int i = 0; i < elements.Count - 1; i++)
        {
            if (elements[i].Name.LocalName == "key")
            {
                var dictElem = elements[i + 1];
                if (dictElem.Name.LocalName == "dict")
                {
                    var track = ParseTrackElement(dictElem);
                    if (track != null)
                    {
                        library.Tracks[track.TrackId] = track;
                    }
                }
            }
        }
    }

    private static Track? ParseTrackElement(XElement trackDict)
    {
        int trackId = 0;
        string name = string.Empty;
        string? artist = null;
        string? album = null;
        string? location = null;
        long size = 0;

        var nodes = trackDict.Elements().ToList();
        for (int i = 0; i < nodes.Count - 1; i++)
        {
            if (nodes[i].Name.LocalName == "key")
            {
                string key = nodes[i].Value;
                var valElem = nodes[i + 1];

                switch (key)
                {
                    case "Track ID":
                        int.TryParse(valElem.Value, out trackId);
                        break;
                    case "Name":
                        name = valElem.Value;
                        break;
                    case "Artist":
                        artist = valElem.Value;
                        break;
                    case "Album":
                        album = valElem.Value;
                        break;
                    case "Location":
                        location = DecodeFileUrl(valElem.Value);
                        break;
                    case "Size":
                        long.TryParse(valElem.Value, out size);
                        break;
                }
            }
        }

        if (trackId > 0 && !string.IsNullOrEmpty(name))
        {
            return new Track(trackId, name, artist, album, location, size);
        }

        return null;
    }

    private static void ParsePlaylistsArray(XElement playlistsArray, ITunesLibrary library)
    {
        foreach (var playlistDict in playlistsArray.Elements("dict"))
        {
            var playlist = ParsePlaylistElement(playlistDict);
            if (playlist != null)
            {
                library.Playlists.Add(playlist);
            }
        }
    }

    private static Playlist? ParsePlaylistElement(XElement playlistDict)
    {
        int playlistId = 0;
        string name = string.Empty;
        var trackIds = new List<int>();
        string? persistentId = null;
        string? parentPersistentId = null;
        bool isFolder = false;
        int? distinguishedKind = null;
        bool isMaster = false;
        bool isVisible = true;

        var nodes = playlistDict.Elements().ToList();
        for (int i = 0; i < nodes.Count - 1; i++)
        {
            if (nodes[i].Name.LocalName == "key")
            {
                string key = nodes[i].Value;
                var valElem = nodes[i + 1];

                switch (key)
                {
                    case "Playlist ID":
                        int.TryParse(valElem.Value, out playlistId);
                        break;
                    case "Name":
                        name = valElem.Value;
                        break;
                    case "Playlist Persistent ID":
                        persistentId = valElem.Value;
                        break;
                    case "Parent Persistent ID":
                        parentPersistentId = valElem.Value;
                        break;
                    case "Folder":
                        isFolder = valElem.Name.LocalName == "true";
                        break;
                    case "Distinguished Kind":
                        if (int.TryParse(valElem.Value, out int dk))
                        {
                            distinguishedKind = dk;
                        }
                        break;
                    case "Master":
                        isMaster = valElem.Name.LocalName == "true";
                        break;
                    case "Visible":
                        isVisible = valElem.Name.LocalName != "false";
                        break;
                    case "Playlist Items":
                        if (valElem.Name.LocalName == "array")
                        {
                            foreach (var itemDict in valElem.Elements("dict"))
                            {
                                var itemNodes = itemDict.Elements().ToList();
                                for (int j = 0; j < itemNodes.Count - 1; j++)
                                {
                                    if (itemNodes[j].Name.LocalName == "key" && itemNodes[j].Value == "Track ID")
                                    {
                                        if (int.TryParse(itemNodes[j + 1].Value, out int tid))
                                        {
                                            trackIds.Add(tid);
                                        }
                                    }
                                }
                            }
                        }
                        break;
                }
            }
        }

        // Masterライブラリ、非表示項目、およびiTunesシステム特殊プレイリスト（ミュージック、ダウンロード済み、ムービー、Genius等）は除外
        if (isMaster || !isVisible || distinguishedKind.HasValue)
        {
            return null;
        }

        if (playlistId > 0 && !string.IsNullOrEmpty(name))
        {
            return new Playlist(playlistId, name, trackIds, persistentId, parentPersistentId, isFolder, distinguishedKind);
        }

        return null;
    }

    public static string? DecodeFileUrl(string url)
    {
        try
        {
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                string localPath = uri.LocalPath;
                // \\localhost\M:\path を M:\path に正規化
                if (localPath.StartsWith(@"\\localhost\", StringComparison.OrdinalIgnoreCase))
                {
                    localPath = localPath.Substring(@"\\localhost\".Length);
                }
                return localPath;
            }
            return url;
        }
        catch
        {
            return url;
        }
    }
}
