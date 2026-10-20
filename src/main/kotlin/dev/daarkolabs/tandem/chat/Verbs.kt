// The CLI's static spinner words (90, v1.0.29 onward), from S18 levindixon/tengu_spinner_words (README, fetched 2026-10-20); the CLI merges more from its tengu_spinner_words config at runtime, which Tandem does not fetch. Extend from the gist in S18 when reachable.
package dev.daarkolabs.tandem.chat

/**
 * The working indicator's present participles (spec appendix A), alphabetical so a diff against the source is one read. Appendix A
 * lists `Mustering, Musing`; they are swapped here into alphabetical order.
 */
internal val SPINNER_VERBS: List<String> = listOf(
    "Accomplishing", "Actioning", "Actualizing", "Baking", "Booping", "Brewing", "Calculating", "Cerebrating", "Channelling", "Churning",
    "Clauding", "Coalescing", "Cogitating", "Combobulating", "Computing", "Concocting", "Conjuring", "Considering", "Contemplating", "Cooking",
    "Crafting", "Creating", "Crunching", "Deciphering", "Deliberating", "Determining", "Discombobulating", "Divining", "Doing", "Effecting",
    "Elucidating", "Enchanting", "Envisioning", "Finagling", "Flibbertigibbeting", "Forging", "Forming", "Frolicking", "Generating", "Germinating",
    "Hatching", "Herding", "Honking", "Hustling", "Ideating", "Imagining", "Incubating", "Inferring", "Jiving", "Manifesting",
    "Marinating", "Meandering", "Moseying", "Mulling", "Musing", "Mustering", "Noodling", "Percolating", "Perusing", "Philosophising",
    "Pondering", "Pontificating", "Processing", "Puttering", "Puzzling", "Reticulating", "Ruminating", "Scheming", "Schlepping", "Shimmying",
    "Shucking", "Simmering", "Smooshing", "Spelunking", "Spinning", "Stewing", "Sussing", "Synthesizing", "Thinking", "Tinkering",
    "Transmuting", "Unfurling", "Unravelling", "Vibing", "Wandering", "Whirring", "Wibbling", "Wizarding", "Working", "Wrangling",
)
