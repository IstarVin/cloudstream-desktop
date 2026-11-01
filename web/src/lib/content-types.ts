// Cloudstream's TvType names, grouped into readable browsing filters.
export const CONTENT_TYPES = [
  { id: "anime", label: "Anime", types: ["Anime", "AnimeMovie", "OVA"] },
  { id: "movies", label: "Movies", types: ["Movie", "AnimeMovie"] },
  { id: "series", label: "TV Series", types: ["TvSeries"] },
  { id: "drama", label: "Asian Drama", types: ["AsianDrama"] },
  { id: "cartoons", label: "Cartoons", types: ["Cartoon"] },
  { id: "documentaries", label: "Documentaries", types: ["Documentary"] },
  { id: "live", label: "Live", types: ["Live"] },
  { id: "torrent", label: "Torrents", types: ["Torrent"] },
  { id: "adult", label: "Adult", types: ["NSFW"] },
  { id: "other", label: "Other", types: ["Others", "Music", "AudioBook", "CustomMedia"] },
] as const;

export function matchesContentType(types: string[], filter: string): boolean {
  if (filter === "all") return true;
  const group = CONTENT_TYPES.find((group) => group.id === filter);
  return !!group && types.some((type) => (group.types as readonly string[]).includes(type));
}

export function contentTypeLabels(types: string[]): string {
  return (
    CONTENT_TYPES.filter((group) => matchesContentType(types, group.id))
      .map((group) => group.label)
      .join(" · ") || "Unspecified content"
  );
}
