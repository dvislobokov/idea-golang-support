package completion

// Live probes of the catalogue in basic completion (GoLand dump probes 8, 10, 10b) and of the heuristic ranker.

func packagesOfAName() {
	// caret: type "json.Mar" on the next line, Basic: json.Marshal (encoding/json), json.Marshaler, and the rows of encoding/json/v2 with the path "encoding/json/v2" as type text; pick the v2 Marshal -> import "encoding/json/v2"

	// caret: type "cl" on the next line, auto-popup: clear, close, http.Client, strings.Clone, os.Clearenv, slices.Clip, path.Clean… (already so in 0.2.189)

	// caret: type a name of an indirect dependency of the playground module (go.sum without "// indirect" in go.mod is direct): its rows follow the direct modules' rows of the same name
}

func rankerAlphaRun()  {}
func rankerAlphaStop() {}

func rankerFrequency() {
	rankerAlphaStop()
	rankerAlphaStop()
	// caret: type "rankerAlpha" on the next line, Basic: rankerAlphaStop before rankerAlphaRun (used in the file); choose rankerAlphaRun once, then again: rankerAlphaRun first (recency)

}
