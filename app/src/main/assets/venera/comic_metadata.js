// Preserve optional metadata supplied by sources without changing the bundled Venera runtime.
(() => {
    const fields = ["author", "authors", "artist", "alternateTitles", "aliases", "otherNames",
        "status", "isCompleted", "language", "originalLanguage", "updatedAt"];
    const extend = (Original) => {
        function WithMetadata(options) {
            const result = new Original(options);
            for (const field of fields) {
                if (Object.prototype.hasOwnProperty.call(options, field)) result[field] = options[field];
            }
            return result;
        }
        WithMetadata.prototype = Original.prototype;
        return WithMetadata;
    };
    Comic = extend(Comic);
    ComicDetails = extend(ComicDetails);
})();
