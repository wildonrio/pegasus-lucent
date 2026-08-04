import QtQuick 2.9
import QtMultimedia 5.9
import QtGraphicalEffects 1.0
import SortFilterProxyModel 0.2

FocusScope {
    id: root
    focus: true
    width: 1920
    height: 1080

    property string page: "home"
    property int homeZone: 0 // 0 systems, 1 continue, 2 most played, 3 top games
    property bool previewReady: false
    property double previewRequestSequence: Date.now()
    property double currentBottomPreviewSequence: 0
    property var currentBottomPreviewGame: null
    property bool launchPollPending: false
    property bool applicationWasActive: false
    // Capability detection keeps physical lower-display playback exclusive to
    // dual-screen hardware. Single-screen devices use the in-theme PIP below.
    property bool dualScreenDevice: true
    property string previewPlacementMode: "auto" // auto, bottom, top, off
    property bool previewSoundEnabled: true
    // System identity is intentionally static while selected. Every system
    // owns one decoded wallpaper and receives its next random wallpaper only
    // after the user leaves it. The experimental motion layer was removed
    // because decoder startup could replace the visible artwork after entry.
    property bool systemMotionEnabled: false
    property bool systemVideoStarted: false
    property string currentSystemMotionSource: ""
    property bool settingsOpen: false
    property int settingsIndex: 0
    property bool liquidGlassEnabled: false
    property bool searchOpen: false
    property string searchQuery: ""
    property bool searchKeyboardAccepting: false
    property int singleCurrentSlot: -1
    property int singleTargetSlot: -1
    property string singleSourceA: ""
    property string singleSourceB: ""
    property string singleSourceC: ""
    property var activePreviewSlot: null
    property var homePreviewGame: null
    property var departedHomeSlot: null
    property var lastHomeGameBySystem: ({})
    property var homePreviewCandidatesBySystem: ({})
    property int lastSystemPreviewIndex: -1
    property int departedSystemIndex: -1
    property string sortMode: "user"
    property string gameViewMode: "covers"
    property string importState: "idle"
    // A recreated Qt scene polls the companion's last completed status. Treat
    // that first completed response as a silent baseline so returning from a
    // game never resurrects an old "library update complete" notification.
    property bool importStatusInitialized: false
    property real importProgress: 0
    property string importMessage: ""
    property var importTitles: []
    property int importIdentified: 0
    property int importAdded: 0
    property bool importNeedsReload: false
    property bool importToastVisible: false
    property string lastImportFingerprint: ""
    property var hardwarePhotoBySystem: []
    property int lastHardwareSystemIndex: -1
    property int upperArtworkSlot: 0
    property int upperArtworkPendingSlot: -1
    property string upperArtworkTarget: ""
    // Capture the system that owns the open game page. Some Android controller
    // key-up events can arrive while the home ListView is losing focus; using
    // its live currentIndex after that transition can open/display a stale
    // neighboring collection.
    property int activeSystemIndex: 0
    property bool allSystemsActive: false
    property int activeGameSystemIndex: systemIndexForGame(activeGame)
    property int shelfDisplaySystemIndex: page === "home" && homeZone > 0 ?
            activeGameSystemIndex : -1
    property int displaySystemIndex: {
        if (page === "games" && allSystemsActive && activeGameSystemIndex > 0)
            return activeGameSystemIndex
        if (page === "games")
            return activeSystemIndex
        if (shelfDisplaySystemIndex > 0)
            return shelfDisplaySystemIndex
        return systemRail.currentIndex
    }
    // Keep the highlighted home system separate from the collection backing
    // gameSortModel. Rebinding that source on every home-screen arrow forced a
    // complete proxy-model rebuild and score sort for a list that was hidden.
    property var selectedCollection: systemRail.currentIndex === 0 ? null :
            collectionNamed(systemModel.get(systemRail.currentIndex).collectionName)
    property var activeCollection: null
    property var activeGame: {
        if (page === "games" && gameSortModel.count > 0)
            return gameAtDisplayIndex(gameRail.currentIndex)
        if (page === "home" && homeZone > 0)
            return homeShelfGame(homeZone)
        return homePreviewGame
    }
    property color accent: systemModel.get(displaySystemIndex).accent
    property string clockText: ""

    /*
     * Qt 5 has no native Liquid Glass material, so reproduce the optical
     * behavior in one GPU pass. The rounded-rectangle SDF identifies the
     * physical edge of the lens; the background is displaced along that
     * edge normal, lightly scattered in the body, split spectrally at the
     * rim, then lit from the upper-left. This keeps the center readable while
     * making the perimeter visibly refract instead of merely looking blurred.
     */
    property string liquidGlassFragmentShader:
        "varying highp vec2 qt_TexCoord0;\n" +
        "uniform lowp sampler2D source;\n" +
        "uniform highp vec2 glassSize;\n" +
        "uniform highp float cornerRadius;\n" +
        "uniform highp float edgeThickness;\n" +
        "uniform highp float distortionStrength;\n" +
        "uniform highp float scatterRadius;\n" +
        "uniform highp float samplePadding;\n" +
        "uniform lowp vec4 glassTint;\n" +
        "uniform lowp float qt_Opacity;\n" +
        "highp float roundedBox(highp vec2 p, highp vec2 b, highp float r) {\n" +
        "    highp vec2 q = abs(p) - (b - vec2(r));\n" +
        "    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;\n" +
        "}\n" +
        "void main() {\n" +
        "    highp vec2 uv = qt_TexCoord0;\n" +
        "    highp vec2 safeSize = max(glassSize, vec2(1.0));\n" +
        "    highp vec2 sampleExtent = safeSize + vec2(samplePadding * 2.0);\n" +
        "    highp vec2 texel = 1.0 / sampleExtent;\n" +
        "    highp vec2 baseUv = (uv * safeSize + vec2(samplePadding)) / sampleExtent;\n" +
        "    highp vec2 halfBox = safeSize * 0.5;\n" +
        "    highp vec2 p = uv * safeSize - halfBox;\n" +
        "    highp float d = roundedBox(p, halfBox, cornerRadius);\n" +
        "    highp float mask = 1.0 - smoothstep(-0.75, 0.75, d);\n" +
        "    highp float insideDistance = max(-d, 0.0);\n" +
        "    highp float edge = 1.0 - smoothstep(0.8, edgeThickness, insideDistance);\n" +
        "    highp vec2 dx = vec2(1.0, 0.0);\n" +
        "    highp vec2 dy = vec2(0.0, 1.0);\n" +
        "    highp vec2 grad = vec2(\n" +
        "        roundedBox(p + dx, halfBox, cornerRadius) - roundedBox(p - dx, halfBox, cornerRadius),\n" +
        "        roundedBox(p + dy, halfBox, cornerRadius) - roundedBox(p - dy, halfBox, cornerRadius));\n" +
        "    highp vec2 normal = normalize(grad + vec2(0.0001));\n" +
        "    highp float lensRipple = 0.78 + 0.22 * cos(insideDistance * 0.42);\n" +
        "    highp float bend = distortionStrength * edge * edge * lensRipple;\n" +
        "    highp vec2 lensUv = clamp(baseUv - normal * bend * texel, texel, vec2(1.0) - texel);\n" +
        "    highp vec2 sx = vec2(scatterRadius * texel.x, 0.0);\n" +
        "    highp vec2 sy = vec2(0.0, scatterRadius * texel.y);\n" +
        "    lowp vec4 sampleColor = texture2D(source, lensUv) * 0.24;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sy, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sy, texel, vec2(1.0) - texel)) * 0.11;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx + sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv + sx - sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx + sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    sampleColor += texture2D(source, clamp(lensUv - sx - sy, texel, vec2(1.0) - texel)) * 0.08;\n" +
        "    lowp vec3 color = sampleColor.rgb;\n" +
        "    highp float chroma = 1.35 * edge;\n" +
        "    highp vec2 redUv = clamp(lensUv - normal * chroma * texel, texel, vec2(1.0) - texel);\n" +
        "    highp vec2 blueUv = clamp(lensUv + normal * chroma * texel, texel, vec2(1.0) - texel);\n" +
        "    color.r = mix(color.r, texture2D(source, redUv).r, edge * 0.30);\n" +
        "    color.b = mix(color.b, texture2D(source, blueUv).b, edge * 0.30);\n" +
        "    highp float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));\n" +
        "    highp float adaptiveDim = 0.40 * smoothstep(0.46, 0.86, luminance);\n" +
        "    color *= 1.0 - adaptiveDim;\n" +
        "    color = mix(color, glassTint.rgb, glassTint.a);\n" +
        "    highp vec2 lightDirection = normalize(vec2(-0.62, -0.78));\n" +
        "    highp float litRim = pow(max(dot(normal, lightDirection), 0.0), 3.0) * edge;\n" +
        "    highp float darkRim = pow(max(dot(normal, -lightDirection), 0.0), 2.0) * edge;\n" +
        "    highp float innerCaustic = smoothstep(0.8, 3.0, insideDistance) *\n" +
        "        (1.0 - smoothstep(3.0, 9.0, insideDistance));\n" +
        "    color += vec3(0.14, 0.16, 0.19) * litRim;\n" +
        "    color += vec3(0.045, 0.05, 0.06) * innerCaustic;\n" +
        "    color *= 1.0 - 0.17 * darkRim;\n" +
        "    gl_FragColor = vec4(color * mask, mask) * qt_Opacity;\n" +
        "}\n"

    function brandSlugForSystem(index) {
        var folder = systemModel.get(index).folder
        if (folder === "all") return ""
        if (folder === "arcade") return "arcade"
        if (folder === "megadrive" || folder === "dreamcast" || folder === "gamegear")
            return "sega"
        if (folder === "psx" || folder === "ps2" || folder === "ps3" ||
                folder === "psp" || folder === "psvita")
            return "sony"
        if (folder === "windows") return "microsoft"
        return "nintendo"
    }

    function brandNameForSystem(index) {
        var brand = brandSlugForSystem(index)
        if (brand === "sega") return "SEGA"
        if (brand === "sony") return "SONY"
        if (brand === "microsoft") return "MICROSOFT"
        if (brand === "nintendo") return "NINTENDO"
        if (brand === "arcade") return "ARCADE"
        return "MULTI-PUBLISHER"
    }

    function brandLogoForSystem(index) {
        var brand = brandSlugForSystem(index)
        if (brand === "arcade")
            return Qt.resolvedUrl("assets/logos-png/arcade.png")
        return brand ? Qt.resolvedUrl("assets/brands/" + brand + ".png") : ""
    }

    function hardwareVariants(folder) {
        // Rotate every audited real-hardware angle available for the platform.
        // Only folders that physically contain fewer source photographs are
        // restricted; no generated stand-ins are used.
        if (folder === "all") return [0]
        if (folder === "psx") return [0, 1]
        if (folder === "nds") return [0, 1, 2, 3]
        if (folder === "arcade" || folder === "n64" ||
                folder === "ps2" || folder === "psp")
            return [0, 1, 2, 3, 4]
        return [0, 1, 2]
    }

    function hardwarePhotoUrl(index, variant) {
        var folder = systemModel.get(index).folder
        return Qt.resolvedUrl("assets/hardware-cutouts/" + folder + "/" + variant + ".png")
    }

    function initializeHardwarePhotos() {
        var photos = []
        for (var index = 0; index < systemModel.count; ++index) {
            var variants = hardwareVariants(systemModel.get(index).folder)
            photos[index] = hardwarePhotoUrl(index,
                    variants[Math.floor(Math.random() * variants.length)])
        }
        hardwarePhotoBySystem = photos
        lastHardwareSystemIndex = systemRail.currentIndex
    }

    function rerollHardwarePhoto(index) {
        if (index < 0 || index >= systemModel.count) return
        var variants = hardwareVariants(systemModel.get(index).folder)
        var current = String(hardwarePhotoBySystem[index] || "")
        var match = current.match(/\/(\d+)\.png(?:\?.*)?$/)
        var previous = match ? Number(match[1]) : -1
        var next = variants[Math.floor(Math.random() * variants.length)]
        if (variants.length > 1 && next === previous) {
            var position = variants.indexOf(next)
            next = variants[(position + 1 + Math.floor(Math.random() *
                    (variants.length - 1))) % variants.length]
        }
        var photos = hardwarePhotoBySystem.slice(0)
        photos[index] = hardwarePhotoUrl(index, next)
        hardwarePhotoBySystem = photos
    }

    function collectionNamed(name) {
        for (var i = 0; i < api.collections.count; ++i) {
            var candidate = api.collections.get(i)
            if (candidate.name === name)
                return candidate
        }
        return null
    }

    function recentGame(index) {
        if (index < 0 || index >= recentModel.count)
            return null
        return api.allGames.get(recentModel.mapToSource(index))
    }

    function proxyGame(proxy, index) {
        if (!proxy || index < 0 || index >= proxy.count)
            return null
        return api.allGames.get(proxy.mapToSource(index))
    }

    function mostPlayedGame(index) {
        return proxyGame(mostPlayedModel, index)
    }

    function topGame(index) {
        return proxyGame(topGamesModel, index)
    }

    function homeShelfGame(zone) {
        if (zone === 1) return recentGame(recentRail.currentIndex)
        if (zone === 2) return mostPlayedGame(mostPlayedRail.currentIndex)
        if (zone === 3) return topGame(topGamesRail.currentIndex)
        return null
    }

    function homeShelfName(zone) {
        if (zone === 1) return "CONTINUE PLAYING"
        if (zone === 2) return "MOST PLAYED"
        return "TOP GAMES"
    }

    function systemIndexForGame(game) {
        if (!game || !game.collections || game.collections.count <= 0)
            return -1
        for (var collectionIndex = 0; collectionIndex < game.collections.count; ++collectionIndex) {
            var collectionName = game.collections.get(collectionIndex).name
            for (var systemIndex = 0; systemIndex < systemModel.count; ++systemIndex) {
                if (systemModel.get(systemIndex).collectionName === collectionName)
                    return systemIndex
            }
        }
        return -1
    }

    function markForGame(game) {
        var index = systemIndexForGame(game)
        return index >= 0 ? systemModel.get(index).mark : "GAME"
    }

    function accentForGame(game) {
        var index = systemIndexForGame(game)
        return index >= 0 ? systemModel.get(index).accent : root.accent
    }

    function artwork(game) {
        if (!game) return ""
        return game.assets.background || game.assets.screenshot || game.assets.boxFront || ""
    }

    function chooseSystemWallpaper(index) {
        var wrapped = wrappedSystemIndex(index)
        if (wrapped < 0) return
        var departed = lastHardwareSystemIndex
        if (hardwarePhotoBySystem.length > 0 && departed >= 0 && departed !== wrapped)
            rerollHardwarePhoto(departed)
        lastHardwareSystemIndex = wrapped
    }

    function upperArtworkSource() {
        if (page === "home" && homeZone === 0)
            return ""
        if (!activeGame) return ""
        // Never replace a game's identity with generic system hardware.  Real
        // fan art remains first choice, then gameplay, then its retail cover.
        return activeGame.assets.background || activeGame.assets.screenshot ||
                activeGame.assets.boxFront || ""
    }

    function upperArtworkLayer(slot) {
        return slot === 0 ? upperArtworkA : upperArtworkB
    }

    function promoteUpperArtwork(slot) {
        if (slot !== upperArtworkPendingSlot)
            return
        var layer = upperArtworkLayer(slot)
        if (!layer || layer.status !== Image.Ready ||
                String(layer.source) !== String(upperArtworkTarget))
            return
        upperArtworkSlot = slot
        upperArtworkPendingSlot = -1
    }

    function queueUpperArtwork(game, previousGame, nextGame) {
        // Decode both likely D-pad destinations before they are selected.
        // Assigning one of these URLs to the visible standby buffer then hits
        // Qt's image cache instead of briefly exposing the black base layer.
        upperArtworkPreloadPrevious.source = artwork(previousGame)
        upperArtworkPreloadNext.source = artwork(nextGame)

        var requested = String(artwork(game) || "")
        if (!requested)
            return
        var activeLayer = upperArtworkLayer(upperArtworkSlot)
        if (activeLayer && String(activeLayer.source) === requested) {
            // Rapidly moving away and straight back can leave a later request
            // decoding in the standby slot. Cancel its promotion so it cannot
            // replace the artwork the user has already returned to.
            upperArtworkTarget = requested
            upperArtworkPendingSlot = -1
            return
        }
        var incomingSlot = upperArtworkSlot === 0 ? 1 : 0
        var incomingLayer = upperArtworkLayer(incomingSlot)
        upperArtworkTarget = requested
        upperArtworkPendingSlot = incomingSlot
        incomingLayer.source = requested
        if (incomingLayer.status === Image.Ready)
            promoteUpperArtwork(incomingSlot)
    }

    function numericExtra(game, key) {
        if (!game || !game.extra) return NaN
        var raw = game.extra[key]
        // Pegasus preserves punctuation in x-* keys on this Android build.
        // Accept both API spellings so metadata refreshes cannot blank scores.
        if ((raw === undefined || raw === null || raw === "") && key === "userScore")
            raw = game.extra["user-score"] || game.extra["metacritic-user"]
        if ((raw === undefined || raw === null || raw === "") && key === "userComposite")
            raw = game.extra["user-composite"]
        if ((raw === undefined || raw === null || raw === "") && key === "criticComposite")
            raw = game.extra["critic-composite"]
        var value = Number(raw)
        return isNaN(value) ? NaN : value
    }

    function userScore(game) {
        // `rating` is reserved as the native, fast critic-sort role. Keep the
        // displayed user score sourced from its explicit metadata field.
        var score = numericExtra(game, "userComposite")
        if (isNaN(score)) score = numericExtra(game, "userScore")
        return isNaN(score) || score <= 0 ? NaN : score
    }

    function criticScore(game) {
        var score = numericExtra(game, "criticComposite")
        if (isNaN(score)) {
            score = game && game.extra ? Number(game.extra.critic) : NaN
            if (!isNaN(score) && score > 10) score /= 10.0
        }
        return isNaN(score) || score <= 0 ? NaN : score
    }

    function criticLabel(game) {
        return "CRITICS"
    }

    function scoreText(game) {
        var critic = criticScore(game)
        var user = userScore(game)
        return "CRITICS  " + (isNaN(critic) ? "N/A" : critic.toFixed(1)) +
                "     USERS  " + (isNaN(user) ? "N/A" : user.toFixed(1))
    }

    function releaseYear(game) {
        if (!game || !game.release) return "N/A"
        var formatted = Qt.formatDate(game.release, "yyyy")
        if (formatted && formatted !== "0" && formatted !== "NaN")
            return formatted
        var match = String(game.release).match(/(?:19|20)\d{2}/)
        return match ? match[0] : "N/A"
    }

    function gameFactsText(game) {
        return scoreText(game) + "     RELEASE  " + releaseYear(game)
    }

    function gameAtDisplayIndex(index) {
        if ((!allSystemsActive && !activeCollection) || gameSortModel.count <= 0 ||
                index < 0 || index >= gameSortModel.count)
            return null
        return allSystemsActive ? api.allGames.get(gameSortModel.mapToSource(index)) :
                activeCollection.games.get(gameSortModel.mapToSource(index))
    }

    function cycleSort(direction) {
        var modes = ["critic", "user", "alpha", "release"]
        var current = modes.indexOf(sortMode)
        var step = direction === -1 ? -1 : 1
        sortMode = modes[(current + step + modes.length) % modes.length]
        api.memory.set("thoriumSortMode", sortMode)
        gameRail.currentIndex = 0
        // The proxy can keep the same row index while replacing the game at
        // that row. Give it one layout turn, then refresh by game identity.
        sortChangeCommit.restart()
    }

    function toggleGameView() {
        gameViewMode = gameViewMode === "covers" ? "list" : "covers"
        api.memory.set("thoriumGameView", gameViewMode)
        Qt.callLater(function() {
            if (gameViewMode === "list")
                gameListRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
            else
                gameRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
        })
    }

    function sortLabel(mode) {
        if (mode === "user") return "USER SCORE"
        if (mode === "critic") return "CRITIC SCORE"
        if (mode === "release") return "RELEASE"
        return "A–Z"
    }

    function videoSource(game) {
        return game && game.assets.video ? game.assets.video : ""
    }

    function boxAspectForSystem(index) {
        // Width / height for the physical packaging used by each platform.
        // Games within one system always share the same visual canvas.
        var folder = systemModel.get(index).folder
        if (folder === "n64" || folder === "snes") return 1.43
        if (folder === "psx" || folder === "dreamcast") return 1.0
        if (folder === "nds") return 0.86
        if (folder === "ps3") return 0.79
        if (folder === "psp") return 0.58
        if (folder === "gb" || folder === "gba" || folder === "gbc") return 1.0
        return 0.70
    }

    function previewUrl(game, previousGame, nextGame, auxiliaryGame) {
        previewRequestSequence += 1
        currentBottomPreviewSequence = previewRequestSequence
        var video = videoSource(game)
        var art = game ? artwork(game) : ""
        var title = game ? (game.title || "") : ""
        var gameSystemIndex = systemIndexForGame(game)
        var systemName = gameSystemIndex >= 0 ? systemModel.get(gameSystemIndex).name : ""
        var score = game ? scoreText(game) : ""
        return "http://127.0.0.1:43821/play?seq=" + previewRequestSequence +
                "&video=" + encodeURIComponent(video) +
                "&art=" + encodeURIComponent(art) +
                "&title=" + encodeURIComponent(title) +
                "&system=" + encodeURIComponent(systemName) +
                "&score=" + encodeURIComponent(score) +
                "&preload_prev=" + encodeURIComponent(videoSource(previousGame)) +
                "&preload_next=" + encodeURIComponent(videoSource(nextGame)) +
                "&preload_aux=" + encodeURIComponent(videoSource(auxiliaryGame))
    }

    function sendBottomPreview(game, previousGame, nextGame, auxiliaryGame) {
        if (previewPlacementMode === "off") {
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            singleCurrentSlot = -1
            requestPreviewEndpoint("blank")
            return
        }
        if (!useBottomPreview()) {
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            requestPreviewEndpoint("blank")
            setSingleScreenPreview(videoSource(game), videoSource(previousGame),
                                   videoSource(nextGame))
            return
        }
        currentBottomPreviewGame = game
        var url = previewUrl(game, previousGame, nextGame, auxiliaryGame)
        var request = new XMLHttpRequest()
        request.open("GET", url, true)
        request.send()
    }

    function useBottomPreview() {
        if (!dualScreenDevice) return false
        return previewPlacementMode === "auto" || previewPlacementMode === "bottom"
    }

    function requestPreviewEndpoint(endpoint) {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/" + endpoint, true)
        request.send()
    }

    function pollBottomLaunchRequest() {
        if (launchPollPending || !useBottomPreview() || !currentBottomPreviewGame)
            return
        launchPollPending = true
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE)
                return
            root.launchPollPending = false
            if (request.status !== 200)
                return
            try {
                var payload = JSON.parse(request.responseText)
                var requestedSequence = Number(payload.seq || 0)
                if (requestedSequence > 0 &&
                        requestedSequence === root.currentBottomPreviewSequence &&
                        root.currentBottomPreviewGame)
                    root.launch(root.currentBottomPreviewGame)
            } catch (error) {
                // A missing optional companion must never affect navigation.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/launch/status", true)
        request.send()
    }

    function refreshCurrentPreview() {
        if (previewPlacementMode === "off") {
            singleCurrentSlot = -1
            currentBottomPreviewGame = null
            currentBottomPreviewSequence = 0
            requestPreviewEndpoint("blank")
            return
        }
        if (!useBottomPreview())
            requestPreviewEndpoint("blank")
        else
            singleCurrentSlot = -1
        if (page === "home" && homeZone === 0)
            activateHomePreview(false)
        else if (page === "games")
            activateGamePreview()
        else
            activateShelfPreview(homeZone)
    }

    function cyclePreviewPlacement(direction) {
        var modes = dualScreenDevice ? ["auto", "bottom", "top", "off"] :
                                       ["auto", "top", "off"]
        var current = modes.indexOf(previewPlacementMode)
        if (current < 0) current = 0
        previewPlacementMode = modes[(current + direction + modes.length) % modes.length]
        api.memory.set("thoriumPreviewPlacement", previewPlacementMode)
        refreshCurrentPreview()
    }

    function setPreviewSoundEnabled(enabled) {
        previewSoundEnabled = Boolean(enabled)
        api.memory.set("thoriumPreviewSound", previewSoundEnabled)
        requestPreviewEndpoint("settings/sound?enabled=" +
                (previewSoundEnabled ? "1" : "0"))
    }

    function previewPlacementLabel() {
        if (previewPlacementMode === "bottom") return "LOWER DISPLAY"
        if (previewPlacementMode === "top") return "TOP-RIGHT PIP"
        if (previewPlacementMode === "off") return "OFF"
        return dualScreenDevice ? "AUTOMATIC (LOWER)" : "AUTOMATIC (PIP)"
    }

    function activateSetting(direction) {
        if (settingsIndex === 0) {
            // Static, predecoded system artwork is the sole supported mode.
            systemMotionEnabled = false
            api.memory.set("thoriumSystemMotion", false)
        } else if (settingsIndex === 1) {
            cyclePreviewPlacement(direction === 0 ? 1 : direction)
        } else if (settingsIndex === 2) {
            setPreviewSoundEnabled(!previewSoundEnabled)
        } else if (settingsIndex === 3) {
            liquidGlassEnabled = !liquidGlassEnabled
            api.memory.set("thoriumLiquidGlassEnabled", liquidGlassEnabled)
        } else {
            // A manual update is intentionally the only scan path after the
            // initial Pegasus launch. Reset the local state so its progress
            // and completion notification are visible even after an earlier
            // successful scan.
            importState = "idle"
            importStatusInitialized = true
            importToastVisible = true
            startImportScan()
        }
    }

    function detectPreviewCapabilities() {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status !== 200)
                return
            try {
                var capabilities = JSON.parse(request.responseText)
                var detectedDualScreen = Boolean(capabilities.dualScreen)
                if (root.dualScreenDevice !== detectedDualScreen) {
                    root.dualScreenDevice = detectedDualScreen
                    if (root.page === "home" && root.homeZone === 0)
                        root.activateHomePreview(false)
                    else if (root.page === "games")
                        root.activateGamePreview()
                    else
                        root.activateShelfPreview(root.homeZone)
                }
            } catch (error) {
                // Default to Thor's physical lower display if an older
                // companion does not expose capability discovery yet.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/capabilities", true)
        request.send()
    }

    function singleSource(index) {
        if (index === 0) return singleSourceA
        if (index === 1) return singleSourceB
        return singleSourceC
    }

    function singlePlayer(index) {
        if (index === 0) return singleVideoA
        if (index === 1) return singleVideoB
        return singleVideoC
    }

    function setSingleSource(index, source) {
        source = String(source || "")
        if (singleSource(index) === source) return
        if (index === 0) singleSourceA = source
        else if (index === 1) singleSourceB = source
        else singleSourceC = source
    }

    function findSingleSource(source) {
        source = String(source || "")
        for (var index = 0; index < 3; ++index) {
            if (source !== "" && singleSource(index) === source)
                return index
        }
        return -1
    }

    function ensureSingleWarm(source, protectedA, protectedB) {
        source = String(source || "")
        if (source === "") return -1
        var existing = findSingleSource(source)
        if (existing >= 0) return existing
        for (var index = 0; index < 3; ++index) {
            if (index !== protectedA && index !== protectedB) {
                setSingleSource(index, source)
                return index
            }
        }
        return -1
    }

    function promoteSingleVideo(index) {
        if (index !== singleTargetSlot) return
        singleCurrentSlot = index
        singleTargetSlot = -1
    }

    function setSingleScreenPreview(current, previous, next) {
        current = String(current || "")
        if (current === "") {
            singleTargetSlot = -1
            singleCurrentSlot = -1
            return
        }
        var currentSlot = findSingleSource(current)
        if (currentSlot < 0) {
            currentSlot = (singleCurrentSlot + 1 + 3) % 3
            setSingleSource(currentSlot, current)
        }
        singleTargetSlot = currentSlot
        var previousSlot = ensureSingleWarm(previous, currentSlot, -1)
        ensureSingleWarm(next, currentSlot, previousSlot)
        var player = singlePlayer(currentSlot)
        if (player.position > 0 || player.status === MediaPlayer.Buffered)
            promoteSingleVideo(currentSlot)
    }

    function hideBottomPreview() {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/hide", true)
        request.send()
    }

    function blankBottomPreviewNow() {
        // Run before the system rail paints its next selection. This prevents
        // the outgoing system's final decoded frame from surviving beneath
        // the new system while its random preview is being selected/decoded.
        var request = new XMLHttpRequest()
        try {
            previewRequestSequence += 1
            request.open("GET", "http://127.0.0.1:43821/transition?seq=" +
                         previewRequestSequence, true)
            request.send()
        } catch (error) {
            // The preview companion is optional; navigation must stay usable.
        }
    }

    function sendPreviewHeartbeat() {
        var request = new XMLHttpRequest()
        request.open("GET", "http://127.0.0.1:43821/heartbeat", true)
        request.send()
    }

    function applyImportStatus(payload) {
        if (!payload) return
        var previousState = importState
        var establishSilentBaseline = !importStatusInitialized &&
                ((payload.state || "idle") === "complete" ||
                 (payload.state || "idle") === "idle")
        importStatusInitialized = true
        var fingerprint = String(payload.state || "idle") + "|" +
                String(payload.progress || 0) + "|" + String(payload.message || "") + "|" +
                String(payload.identified || 0) + "|" + String(payload.added || 0) + "|" +
                String(Boolean(payload.needsReload))
        var changed = fingerprint !== lastImportFingerprint
        lastImportFingerprint = fingerprint
        importState = payload.state || "idle"
        importProgress = Number(payload.progress || 0)
        importMessage = payload.message || ""
        importTitles = payload.titles || []
        importIdentified = Number(payload.identified || 0)
        importAdded = Number(payload.added || 0)
        importNeedsReload = Boolean(payload.needsReload)
        if (establishSilentBaseline) {
            importToastVisible = false
            return
        }
        if (importState === "complete") {
            // Status polling can continue returning "complete" forever. Show
            // the result once on the state transition, then dismiss it on an
            // absolute timer that later polls cannot restart.
            if (previousState !== "complete") {
                importToastVisible = true
                importToastDismiss.restart()
            }
        } else if (importState === "error") {
            if (previousState !== "error") {
                importToastVisible = true
                importToastDismiss.restart()
            }
        } else if (importState !== "idle") {
            importToastVisible = true
        } else if (changed) {
            importToastVisible = false
        }
    }

    function requestImport(endpoint) {
        var request = new XMLHttpRequest()
        request.onreadystatechange = function() {
            if (request.readyState !== XMLHttpRequest.DONE || request.status < 200 || request.status >= 300)
                return
            try {
                root.applyImportStatus(JSON.parse(request.responseText))
            } catch (error) {
                // Import status must never interfere with navigation.
            }
        }
        request.open("GET", "http://127.0.0.1:43821/import/" + endpoint, true)
        request.send()
    }

    function startImportScan() {
        requestImport("scan")
    }

    function pollImportStatus() {
        requestImport("status")
    }

    function usesBottomScreen(game) {
        var index = systemIndexForGame(game)
        if (index < 0) return false
        var folder = systemModel.get(index).folder
        return folder === "nds" || folder === "n3ds" || folder === "wiiu"
    }

    function stopBottomPreviewForLaunch(game) {
        // Wait for the companion to acknowledge the transition before Pegasus
        // yields the foreground. An asynchronous request could be suspended as
        // the emulator opened, leaving the previous game's movie playing.
        var endpoint = usesBottomScreen(game) ? "hide" : "blank"
        var request = new XMLHttpRequest()
        try {
            request.open("GET", "http://127.0.0.1:43821/" + endpoint, false)
            request.send()
        } catch (error) {
            // Launching the game is still preferable if the optional preview
            // companion is temporarily unavailable.
        }
    }

    function wrappedSystemIndex(index) {
        var count = systemModel.count
        if (count <= 0) return -1
        return (index % count + count) % count
    }

    function collectionAtSystem(index) {
        var wrapped = wrappedSystemIndex(index)
        if (wrapped < 0) return null
        return collectionNamed(systemModel.get(wrapped).collectionName)
    }

    function homePreviewCandidates(systemIndex) {
        var wrapped = wrappedSystemIndex(systemIndex)
        if (wrapped < 0) return []
        var cached = homePreviewCandidatesBySystem[wrapped]
        if (cached) return cached
        var collection = collectionAtSystem(wrapped)
        var videoMatches = []
        var artworkMatches = []
        if (systemModel.get(wrapped).folder === "all") {
            for (var allIndex = 0; allIndex < api.allGames.count; ++allIndex) {
                var allCandidate = api.allGames.get(allIndex)
                if (!allCandidate || systemIndexForGame(allCandidate) <= 0) continue
                if (allCandidate.assets.video)
                    videoMatches.push(allCandidate)
                else if (artwork(allCandidate))
                    artworkMatches.push(allCandidate)
            }
        } else if (collection) {
            for (var index = 0; index < collection.games.count; ++index) {
                var candidate = collection.games.get(index)
                if (!candidate) continue
                if (candidate.assets.video)
                    videoMatches.push(candidate)
                else if (artwork(candidate))
                    artworkMatches.push(candidate)
            }
        }
        cached = videoMatches.length > 0 ? videoMatches : artworkMatches
        homePreviewCandidatesBySystem[wrapped] = cached
        return cached
    }

    function randomHomeVideoGame(systemIndex, avoidGame) {
        var candidates = homePreviewCandidates(systemIndex)
        if (candidates.length === 0) return null
        if (candidates.length === 1) return candidates[0]
        var selected = candidates[Math.floor(Math.random() * candidates.length)]
        if (selected === avoidGame) {
            var current = candidates.indexOf(selected)
            selected = candidates[(current + 1 + Math.floor(Math.random() *
                       (candidates.length - 1))) % candidates.length]
        }
        return selected
    }

    function randomVideoGame(collection, avoidGame) {
        if (!collection || collection.games.count <= 0) return null
        var videoMatches = []
        var artworkMatches = []
        for (var i = 0; i < collection.games.count; ++i) {
            var candidate = collection.games.get(i)
            if (!candidate || candidate === avoidGame)
                continue
            if (candidate.assets.video)
                videoMatches.push(candidate)
            else if (artwork(candidate))
                artworkMatches.push(candidate)
        }
        var matches = videoMatches.length > 0 ? videoMatches : artworkMatches
        if (matches.length === 0 && avoidGame)
            return avoidGame
        if (matches.length === 0)
            return collection.games.get(Math.floor(Math.random() * collection.games.count))
        return matches[Math.floor(Math.random() * matches.length)]
    }

    function previewSlots() {
        return [previewA, previewB, previewC]
    }

    function slotFor(mode, systemIndex, gameIndex) {
        var slots = previewSlots()
        for (var i = 0; i < slots.length; ++i) {
            if (slots[i].previewMode === mode &&
                    slots[i].systemIndex === systemIndex &&
                    slots[i].gameIndex === gameIndex)
                return slots[i]
        }
        return null
    }

    function freeSlot(excludedA, excludedB) {
        var slots = previewSlots()
        for (var i = 0; i < slots.length; ++i) {
            if (slots[i] !== excludedA && slots[i] !== excludedB)
                return slots[i]
        }
        return slots[0]
    }

    function assignSlot(slot, mode, systemIndex, gameIndex, game) {
        if (!slot) return
        if (slot.previewMode === mode && slot.systemIndex === systemIndex &&
                slot.gameIndex === gameIndex && slot.previewGame === game)
            return
        slot.previewMode = mode
        slot.systemIndex = systemIndex
        slot.gameIndex = gameIndex
        slot.previewGame = game
    }

    function prepareHomeNeighbor(systemIndex, reservedSlot) {
        var wrapped = wrappedSystemIndex(systemIndex)
        var existing = slotFor("home", wrapped, -1)
        if (existing && existing !== activePreviewSlot)
            return existing
        var slot = freeSlot(activePreviewSlot, reservedSlot)
        var selected = lastHomeGameBySystem[wrapped] ||
                randomHomeVideoGame(wrapped, null)
        lastHomeGameBySystem[wrapped] = selected
        assignSlot(slot, "home", wrapped, -1,
                   selected)
        return slot
    }

    function activateHomePreview(forceReroll) {
        if (!previewReady) return
        var index = wrappedSystemIndex(systemRail.currentIndex)
        if (lastSystemPreviewIndex >= 0 && lastSystemPreviewIndex !== index) {
            departedSystemIndex = lastSystemPreviewIndex
            rerollDepartedPreview.restart()
        }
        lastSystemPreviewIndex = index
        var current = slotFor("home", index, -1) || freeSlot(null, null)
        var avoid = lastHomeGameBySystem[index] || null
        var selected = !forceReroll && avoid ? avoid :
                randomHomeVideoGame(index, avoid)
        assignSlot(current, "home", index, -1, selected)
        lastHomeGameBySystem[index] = selected
        activePreviewSlot = current
        homePreviewGame = current.previewGame
        var previous = prepareHomeNeighbor(index - 1, null)
        var next = prepareHomeNeighbor(index + 1, previous)
        sendBottomPreview(homePreviewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null, null)
    }

    function gameAt(collection, index) {
        if (!collection || collection.games.count <= 0) return null
        var wrapped = (index % collection.games.count + collection.games.count) % collection.games.count
        return collection.games.get(wrapped)
    }

    function prepareGameNeighbor(systemIndex, collection, gameIndex, reservedSlot) {
        if ((!allSystemsActive && !collection) || gameSortModel.count <= 1) return null
        var wrapped = (gameIndex % gameSortModel.count + gameSortModel.count) % gameSortModel.count
        var expectedGame = gameAtDisplayIndex(wrapped)
        var existing = slotFor("game", systemIndex, wrapped)
        if (existing && existing !== activePreviewSlot &&
                existing.previewGame === expectedGame)
            return existing
        var slot = existing && existing !== activePreviewSlot ? existing :
                   freeSlot(activePreviewSlot, reservedSlot)
        assignSlot(slot, "game", systemIndex, wrapped, expectedGame)
        return slot
    }

    function activateGamePreview() {
        if (!previewReady || page !== "games") return
        var collection = activeCollection
        var systemIndex = allSystemsActive ? activeGameSystemIndex : activeSystemIndex
        var gameIndex = gameRail.currentIndex
        if ((!allSystemsActive && !collection) || gameSortModel.count <= 0) {
            homePreviewGame = null
            var emptySlot = activePreviewSlot || previewA
            assignSlot(emptySlot, "game", systemIndex, -1, null)
            activePreviewSlot = emptySlot
            // An empty collection has no selected game. Explicitly clear the
            // secondary display instead of leaving the departed system's last
            // decoded frame running underneath it.
            if (useBottomPreview())
                requestPreviewEndpoint("blank")
            else
                setSingleScreenPreview("", "", "")
            return
        }

        var expectedGame = gameAtDisplayIndex(gameIndex)
        var current = slotFor("game", systemIndex, gameIndex)
        if (!current) {
            current = freeSlot(null, null)
        }
        // A sort changes the game represented by an index without changing
        // the index itself. Never reuse the old movie merely because the row
        // number matches.
        assignSlot(current, "game", systemIndex, gameIndex, expectedGame)
        activePreviewSlot = current
        var previous = prepareGameNeighbor(systemIndex, collection, gameIndex - 1, null)
        var next = prepareGameNeighbor(systemIndex, collection, gameIndex + 1, previous)
        queueUpperArtwork(current.previewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null)
        var homeAux = lastHomeGameBySystem[systemIndex]
        if (!homeAux) {
            homeAux = randomHomeVideoGame(systemIndex, current.previewGame)
            lastHomeGameBySystem[systemIndex] = homeAux
        }
        sendBottomPreview(current.previewGame,
                          previous ? previous.previewGame : null,
                          next ? next.previewGame : null, homeAux)
    }

    function activateShelfPreview(zone) {
        if (!previewReady) return
        var model = zone === 1 ? recentModel :
                    zone === 2 ? mostPlayedModel : topGamesModel
        var rail = zone === 1 ? recentRail :
                   zone === 2 ? mostPlayedRail : topGamesRail
        if (!model || model.count <= 0) return
        var game = proxyGame(model, rail.currentIndex)
        var slot = freeSlot(null, null)
        assignSlot(slot, "shelf" + zone, -1, rail.currentIndex, game)
        activePreviewSlot = slot
        var previous = proxyGame(model, (rail.currentIndex - 1 + model.count) % model.count)
        var next = proxyGame(model, (rail.currentIndex + 1) % model.count)
        queueUpperArtwork(game, previous, next)
        sendBottomPreview(game, previous, next, null)
    }

    function activateRecentPreview() {
        activateShelfPreview(1)
    }

    function launch(game) {
        if (!game) return
        api.memory.set("thoriumSystem", page === "games" ? activeSystemIndex : systemRail.currentIndex)
        api.memory.set("thoriumGame", gameRail.currentIndex)
        api.memory.set("thoriumPage", page)
        // Qt reconstructs this scene when some emulators release the display.
        // Persisting this one-shot marker distinguishes that return from a
        // genuine Pegasus startup, where the Downloads scan should run.
        api.memory.set("parallaxReturningFromGame", true)
        stopBottomPreviewForLaunch(game)
        game.launch()
    }

    function enterCollection() {
        // Pay the model/sort cost only when the user opens this collection.
        // Resolve directly from the highlighted index in this event turn. A
        // derived selectedCollection binding may not have re-evaluated yet
        // after very fast D-pad navigation.
        activeSystemIndex = systemRail.currentIndex
        allSystemsActive = activeSystemIndex === 0
        activeCollection = allSystemsActive ? null : collectionAtSystem(activeSystemIndex)
        page = "games"
        gameRail.currentIndex = 0
        api.memory.set("thoriumSystem", activeSystemIndex)
        api.memory.set("thoriumGame", 0)
        api.memory.set("thoriumPage", "games")
        Qt.callLater(function() { root.activateGamePreview() })
        root.forceActiveFocus()
    }

    function returnHome() {
        systemRail.currentIndex = activeSystemIndex
        page = "home"
        homeZone = 0
        api.memory.set("thoriumSystem", activeSystemIndex)
        api.memory.set("thoriumPage", "home")
        chooseSystemWallpaper(systemRail.currentIndex)
        systemRail.positionViewAtIndex(systemRail.currentIndex, ListView.Center)
        Qt.callLater(function() { root.activateHomePreview(false) })
        systemRail.forceActiveFocus()
    }

    function openSystemInPlace(index) {
        var target = wrappedSystemIndex(index)
        if (target < 0) return
        // Rebind the collection before moving the shared system rail. Its
        // currentIndex callback used to observe the old model and submit one
        // stale preview whenever L2/R2 changed platform.
        activeSystemIndex = target
        allSystemsActive = target === 0
        activeCollection = allSystemsActive ? null : collectionAtSystem(target)
        systemRail.currentIndex = target
        gameRail.currentIndex = 0
        api.memory.set("thoriumSystem", target)
        api.memory.set("thoriumGame", 0)
        api.memory.set("thoriumPage", "games")
        // Keep the root, rather than a delegate that is about to be destroyed
        // by the source-model swap, as the key owner. Losing focus during that
        // swap was what made the first opposite-trigger press disappear.
        root.forceActiveFocus()
        systemOpenCommit.restart()
    }

    function stepOpenSystem(direction) {
        openSystemInPlace(activeSystemIndex + (direction < 0 ? -1 : 1))
    }

    function isLeftTrigger(event) {
        // AYN's Odin Controller exposes L2 both as Android BUTTON_L2 and Linux
        // BTN_TL2. Qt versions used by Pegasus disagree on whether that arrives
        // as a Qt gamepad enum, an Android virtual key, or only a scan code.
        return event.key === 0x01000086 || event.key === 1048581 || event.key === 104 ||
               event.nativeVirtualKey === 104 || event.nativeScanCode === 312
    }

    function isRightTrigger(event) {
        return event.key === 0x01000087 || event.key === 1048584 || event.key === 105 ||
               event.nativeVirtualKey === 105 || event.nativeScanCode === 313
    }

    function triggerDirection(event) {
        // Prefer the Thor's unambiguous Linux scan codes before Pegasus'
        // generic PageUp/PageDown aliases. Some Qt builds cache the previous
        // alias for one event when the user reverses trigger direction.
        if (event.nativeScanCode === 312) return -1
        if (event.nativeScanCode === 313) return 1
        if (isLeftTrigger(event)) return -1
        if (isRightTrigger(event)) return 1
        return 0
    }

    // Qt occasionally drops the pressed edge of the first opposite trigger
    // after a collection model swap, even though Android still delivers its
    // released edge. Track handled presses per physical trigger so release can
    // perform exactly one fallback step without doubling ordinary presses.
    property bool leftTriggerPressHandled: false
    property bool rightTriggerPressHandled: false

    function rememberHandledTrigger(direction) {
        if (direction < 0)
            leftTriggerPressHandled = true
        else if (direction > 0)
            rightTriggerPressHandled = true
    }

    function focusHomeZone(zone) {
        homeZone = Math.max(0, Math.min(3, zone))
        if (homeZone === 0) {
            chooseSystemWallpaper(systemRail.currentIndex)
            activateHomePreview(false)
            systemRail.forceActiveFocus()
        } else if (homeZone === 1) {
            activateShelfPreview(1)
            recentRail.forceActiveFocus()
        } else if (homeZone === 2) {
            activateShelfPreview(2)
            mostPlayedRail.forceActiveFocus()
        } else {
            activateShelfPreview(3)
            topGamesRail.forceActiveFocus()
        }
    }

    function stepHomeShelf(direction) {
        var rail = homeZone === 1 ? recentRail :
                   homeZone === 2 ? mostPlayedRail : topGamesRail
        if (direction < 0)
            rail.decrementCurrentIndex()
        else
            rail.incrementCurrentIndex()
    }

    function stepSystem(direction) {
        var count = systemModel.count
        if (count <= 0) return
        // Match the game rail's cheap navigation path. ApplyRange centers the
        // selected delegate itself; forcing positionViewAtIndex here caused a
        // synchronous relayout on every D-pad press.
        if (direction < 0)
            systemRail.decrementCurrentIndex()
        else
            systemRail.incrementCurrentIndex()
    }

    function updateClock() {
        var now = new Date()
        var hours = now.getHours()
        var minutes = now.getMinutes()
        var suffix = hours >= 12 ? "PM" : "AM"
        hours = hours % 12
        if (hours === 0) hours = 12
        clockText = hours + ":" + (minutes < 10 ? "0" : "") + minutes + " " + suffix
    }

    function escapedSearchPattern(value) {
        var text = String(value || "")
        var special = "\\^$.*+?()[]{}|"
        var result = ""
        for (var i = 0; i < text.length; ++i) {
            var character = text.charAt(i)
            result += special.indexOf(character) >= 0 ? "\\" + character : character
        }
        return result
    }

    function beginSearch() {
        // Search the complete library from home. When invoked inside a system,
        // preserve that system as the search scope.
        if (page === "home") {
            activeSystemIndex = 0
            allSystemsActive = true
            activeCollection = null
            systemRail.currentIndex = 0
            page = "games"
            gameRail.currentIndex = 0
        }
        searchOpen = true
        Qt.callLater(function() {
            searchField.forceActiveFocus()
            Qt.inputMethod.show()
        })
    }

    function endSearch() {
        Qt.inputMethod.reset()
        searchOpen = false
        searchQuery = ""
        searchField.text = ""
        searchField.focus = false
        Qt.inputMethod.hide()
        gameRail.currentIndex = 0
        searchChangeCommit.restart()
        root.forceActiveFocus()
    }

    Component.onCompleted: {
        detectPreviewCapabilities()
        systemMotionEnabled = false
        api.memory.set("thoriumSystemMotion", false)
        previewPlacementMode = api.memory.has("thoriumPreviewPlacement") ?
                api.memory.get("thoriumPreviewPlacement") : "auto"
        // The refractive shader remains available as an explicit visual
        // preference, but the calm, undistorted surface is the default.
        liquidGlassEnabled = api.memory.has("thoriumLiquidGlassEnabled") ?
                Boolean(api.memory.get("thoriumLiquidGlassEnabled")) : false
        // Sound is on by default.  Migrate existing installs once because the
        // original theme persisted OFF even though the requested default is ON.
        if (!api.memory.has("parallaxPreviewSoundDefaultV1")) {
            previewSoundEnabled = true
            api.memory.set("thoriumPreviewSound", true)
            api.memory.set("parallaxPreviewSoundDefaultV1", true)
        } else {
            previewSoundEnabled = api.memory.has("thoriumPreviewSound") ?
                    Boolean(api.memory.get("thoriumPreviewSound")) : true
        }
        requestPreviewEndpoint("settings/sound?enabled=" +
                (previewSoundEnabled ? "1" : "0"))
        var rememberedSystem = api.memory.has("thoriumSystem") ? api.memory.get("thoriumSystem") : 0
        // Version 1 inserts All Systems ahead of the old Arcade index. Shift a
        // saved pre-aggregate selection once so it still points to the same
        // physical platform after the model gains its new first item.
        if (!api.memory.has("thoriumAllSystemsIndexV1") && api.memory.has("thoriumSystem")) {
            rememberedSystem += 1
            api.memory.set("thoriumAllSystemsIndexV1", true)
        }
        systemRail.currentIndex = Math.max(0, Math.min(systemModel.count - 1, rememberedSystem))
        var rememberedGame = api.memory.has("thoriumGame") ? api.memory.get("thoriumGame") : 0
        var rememberedPage = api.memory.has("thoriumPage") ? api.memory.get("thoriumPage") : "home"
        if (!api.memory.has("thoriumCriticSortDefaultV3")) {
            sortMode = "critic"
            api.memory.set("thoriumSortMode", sortMode)
            api.memory.set("thoriumCriticSortDefaultV3", true)
        } else {
            sortMode = api.memory.has("thoriumSortMode") ? api.memory.get("thoriumSortMode") : "user"
        }
        gameViewMode = api.memory.has("thoriumGameView") ?
                api.memory.get("thoriumGameView") : "covers"
        if (rememberedPage === "games") {
            activeSystemIndex = systemRail.currentIndex
            allSystemsActive = activeSystemIndex === 0
            activeCollection = allSystemsActive ? null : collectionAtSystem(activeSystemIndex)
            page = "games"
        } else {
            page = "home"
            homeZone = 0
        }
        initializeHardwarePhotos()
        updateClock()
        previewReady = true
        var returningFromGame = api.memory.has("parallaxReturningFromGame") &&
                Boolean(api.memory.get("parallaxReturningFromGame"))
        api.memory.set("parallaxReturningFromGame", false)
        if (!returningFromGame)
            Qt.callLater(function() { root.startImportScan() })
        else
            Qt.callLater(function() { root.pollImportStatus() })
        Qt.callLater(function() {
            if (root.page === "games") {
                gameRail.currentIndex = Math.max(0,
                        Math.min(gameSortModel.count - 1, rememberedGame))
                if (root.gameViewMode === "list")
                    gameListRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
                else
                    gameRail.positionViewAtIndex(gameRail.currentIndex, ListView.Center)
                root.activateGamePreview()
            } else {
                systemRail.positionViewAtIndex(systemRail.currentIndex, ListView.Center)
                root.activateHomePreview(true)
            }
        })
        root.forceActiveFocus()
    }

    Keys.onPressed: {
        if (searchField.activeFocus) {
            // Text and IME key events must never fall through to Pegasus's
            // controller aliases (for example, the letter A as Accept).
            if (event.nativeScanCode === 305 || event.key === Qt.Key_Back ||
                    event.key === Qt.Key_Escape) {
                endSearch()
                event.accepted = true
            } else {
                event.accepted = false
            }
        } else if (searchOpen && api.keys.isCancel(event)) {
            endSearch()
            event.accepted = true
        } else if (settingsOpen) {
            if (api.keys.isCancel(event) || api.keys.isFilters(event) || api.keys.isDetails(event)) {
                settingsOpen = false
            } else if (event.key === Qt.Key_Up) {
                settingsIndex = Math.max(0, settingsIndex - 1)
            } else if (event.key === Qt.Key_Down) {
                settingsIndex = Math.min(4, settingsIndex + 1)
            } else if (event.key === Qt.Key_Left) {
                activateSetting(-1)
            } else if (event.key === Qt.Key_Right) {
                activateSetting(1)
            } else if (api.keys.isAccept(event)) {
                activateSetting(0)
            }
            event.accepted = true
        } else if (page === "home") {
            if (event.key === Qt.Key_Up) {
                focusHomeZone(homeZone - 1)
                event.accepted = true
            } else if (event.key === Qt.Key_Down) {
                focusHomeZone(homeZone + 1)
                event.accepted = true
            } else if (event.key === Qt.Key_Left) {
                if (homeZone === 0)
                    root.stepSystem(-1)
                else
                    root.stepHomeShelf(-1)
                event.accepted = true
            } else if (event.key === Qt.Key_Right) {
                if (homeZone === 0)
                    root.stepSystem(1)
                else
                    root.stepHomeShelf(1)
                event.accepted = true
            } else if (api.keys.isFilters(event) || api.keys.isDetails(event)) {
                settingsOpen = true
                settingsIndex = 0
                event.accepted = true
            } else if (api.keys.isAccept(event)) {
                if (homeZone === 0)
                    enterCollection()
                else
                    launch(homeShelfGame(homeZone))
                event.accepted = true
            }
        } else {
            var openSystemDirection = triggerDirection(event)
            // Raw Thor trigger identity must win over every generic Pegasus
            // alias. On the first press after reversing L2/R2, this Qt build
            // can briefly retain the previous semantic alias; checking
            // Cancel/PageUp first swallowed that otherwise valid scan code.
            if (openSystemDirection !== 0) {
                rememberHandledTrigger(openSystemDirection)
                stepOpenSystem(openSystemDirection)
                event.accepted = true
            } else if (api.keys.isCancel(event)) {
                returnHome()
                event.accepted = true
            } else if (event.key === 1048576 &&
                       (api.keys.isPageUp(event) || api.keys.isPageDown(event))) {
                // Thor's Qt input plugin reports the same generic pressed key
                // for both triggers and can retain the previous direction's
                // semantic alias. Consume that ambiguous edge; the released
                // edge below carries the correct physical identity.
                event.accepted = true
            } else if (api.keys.isPageUp(event)) {
                stepOpenSystem(-1)
                event.accepted = true
            } else if (api.keys.isPageDown(event)) {
                stepOpenSystem(1)
                event.accepted = true
            } else if (api.keys.isPrevPage(event)) {
                cycleSort(-1)
                event.accepted = true
            } else if (api.keys.isNextPage(event)) {
                cycleSort(1)
                event.accepted = true
            } else if (api.keys.isDetails(event) || api.keys.isFilters(event)) {
                toggleGameView()
                event.accepted = true
            } else if (event.key === Qt.Key_Left) {
                if (gameViewMode === "list")
                    gameRail.currentIndex = Math.max(0, gameRail.currentIndex - 8)
                else
                    gameRail.decrementCurrentIndex()
                event.accepted = true
            } else if (event.key === Qt.Key_Right) {
                if (gameViewMode === "list")
                    gameRail.currentIndex = Math.min(gameRail.count - 1, gameRail.currentIndex + 8)
                else
                    gameRail.incrementCurrentIndex()
                event.accepted = true
            } else if (event.key === Qt.Key_Up) {
                if (gameViewMode === "list")
                    gameRail.decrementCurrentIndex()
                else
                    gameRail.currentIndex = Math.max(0, gameRail.currentIndex - 6)
                event.accepted = true
            } else if (event.key === Qt.Key_Down) {
                if (gameViewMode === "list")
                    gameRail.incrementCurrentIndex()
                else
                    gameRail.currentIndex = Math.min(gameRail.count - 1, gameRail.currentIndex + 6)
                event.accepted = true
            } else if (api.keys.isAccept(event)) {
                launch(activeGame)
                event.accepted = true
            }
        }
    }

    Keys.onReleased: {
        if (searchField.activeFocus || root.page !== "games" || root.settingsOpen)
            return
        var direction = root.triggerDirection(event)
        if (direction < 0) {
            if (!root.leftTriggerPressHandled)
                root.stepOpenSystem(-1)
            root.leftTriggerPressHandled = false
            event.accepted = true
        } else if (direction > 0) {
            if (!root.rightTriggerPressHandled)
                root.stepOpenSystem(1)
            root.rightTriggerPressHandled = false
            event.accepted = true
        }
    }

    Timer {
        id: importPollTimer
        interval: 1200
        running: true
        repeat: true
        onTriggered: root.pollImportStatus()
    }

    Timer {
        id: importToastDismiss
        interval: root.importState === "error" ? 12000 :
                  (root.importAdded > 0 || root.importNeedsReload ? 7000 : 4200)
        repeat: false
        onTriggered: root.importToastVisible = false
    }

    Rectangle {
        id: importToast
        z: 300
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 108
        anchors.rightMargin: 46
        width: 430
        height: root.importIdentified > 0 ? 126 : 96
        visible: root.importToastVisible
        color: "#ee0b0f16"
        border.width: 1
        border.color: root.importState === "error" ? "#ff6d70" : root.accent
        radius: 4

        Rectangle {
            x: 16
            y: 18
            width: 4
            height: 36
            color: root.importState === "error" ? "#ff6d70" : root.accent
        }

        Text {
            x: 34
            y: 15
            width: parent.width - 50
            text: root.importState === "complete" ? "LIBRARY UPDATE COMPLETE" :
                  root.importState === "error" ? "LIBRARY UPDATE PAUSED" :
                  "UPDATING GAME LIBRARY"
            color: "#f2f4f8"
            font.family: global.fonts.condensed
            font.pixelSize: 16
            font.weight: Font.Bold
            font.letterSpacing: 1.6
        }

        Text {
            x: 34
            y: 42
            width: parent.width - 50
            text: root.importMessage
            color: "#aeb7c8"
            elide: Text.ElideRight
            font.family: global.fonts.sans
            font.pixelSize: 12
        }

        Text {
            x: 34
            y: 66
            width: parent.width - 50
            visible: root.importIdentified > 0
            text: {
                var shown = root.importTitles.slice(0, 2).join("  •  ")
                if (root.importTitles.length > 2)
                    shown += "  •  +" + (root.importTitles.length - 2) + " more"
                return shown
            }
            color: root.accent
            elide: Text.ElideRight
            font.family: global.fonts.sans
            font.pixelSize: 11
            font.weight: Font.DemiBold
        }

        Rectangle {
            x: 34
            y: parent.height - 18
            width: parent.width - 50
            height: 3
            color: "#26303e"

            Rectangle {
                width: parent.width * Math.max(0, Math.min(1, root.importProgress))
                height: parent.height
                color: root.importState === "error" ? "#ff6d70" : root.accent
                Behavior on width { NumberAnimation { duration: 260; easing.type: Easing.OutCubic } }
            }
        }
    }

    Timer {
        interval: 700
        repeat: true
        running: true
        triggeredOnStart: true
        onTriggered: {
            var applicationActive = Qt.application.state === Qt.ApplicationActive
            if (applicationActive) {
                if (!root.applicationWasActive && root.previewReady) {
                    // Rebuild the complete warm set after launch/resume. A
                    // current-only refresh would evict the preloaded neighbors
                    // and reintroduce a black frame on the very first move.
                    if (root.page === "home" && root.homeZone === 0)
                        root.activateHomePreview(false)
                    else if (root.page === "games")
                        root.activateGamePreview()
                    else
                        root.activateShelfPreview(root.homeZone)
                }
                root.sendPreviewHeartbeat()
            }
            root.applicationWasActive = applicationActive
        }
    }

    Timer {
        interval: 180
        repeat: true
        running: true
        onTriggered: root.pollBottomLaunchRequest()
    }

    Timer {
        interval: 30000
        repeat: true
        running: true
        onTriggered: root.updateClock()
    }

    // Keep preview selection out of the D-pad event turn. Fast presses update
    // the rail immediately and only the settled selection starts a movie.
    Timer {
        id: systemChangeCommit
        interval: 24
        repeat: false
        onTriggered: {
            if (root.page !== "home" || root.homeZone !== 0)
                return
            root.activateHomePreview(false)
        }
    }

    Timer {
        id: sortChangeCommit
        interval: 36
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (root.gameViewMode === "list")
                gameListRail.positionViewAtIndex(0, ListView.Beginning)
            else
                gameRail.positionViewAtIndex(0, ListView.Beginning)
            root.activateGamePreview()
        }
    }

    Timer {
        id: searchChangeCommit
        interval: 80
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (gameSortModel.count > 0) {
                if (root.gameViewMode === "list")
                    gameListRail.positionViewAtIndex(0, ListView.Beginning)
                else
                    gameRail.positionViewAtIndex(0, ListView.Beginning)
            }
            root.activateGamePreview()
        }
    }

    // A collection source changes synchronously, but its native sorted proxy
    // settles on the next event-loop turn. Coalesce rapid L2/R2 presses and
    // submit the lower-screen selection only after the final platform's row 0
    // exists. This prevents a one-request flash from the departed system.
    Timer {
        id: systemOpenCommit
        interval: 28
        repeat: false
        onTriggered: {
            if (root.page !== "games")
                return
            gameRail.currentIndex = 0
            if (root.gameViewMode === "list")
                gameListRail.positionViewAtIndex(0, ListView.Beginning)
            else
                gameRail.positionViewAtIndex(0, ListView.Beginning)
            root.activateGamePreview()
            root.forceActiveFocus()
        }
    }

    // Once the user settles on a new system, quietly replace the system they
    // just left with another random preview. Fast backtracking stays instant;
    // a later revisit gets a different game instead of item zero every time.
    Timer {
        id: rerollDepartedPreview
        interval: 700
        repeat: false
        onTriggered: {
            var departed = root.departedSystemIndex
            if (departed < 0 || root.page !== "home")
                return
            var oldGame = root.lastHomeGameBySystem[departed] || null
            var replacement = root.randomHomeVideoGame(departed, oldGame)
            root.lastHomeGameBySystem[departed] = replacement
            var slot = root.slotFor("home", departed, -1)
            if (slot && slot !== root.activePreviewSlot)
                root.assignSlot(slot, "home", departed, -1, replacement)
            root.departedSystemIndex = -1
            // The slot above is already refreshed. Do not re-run the current
            // selection and HTTP update merely because a neighbor rerolled.
        }
    }

    ListModel {
        id: systemModel
        // Chronological by first retail release. Years describe the primary
        // hardware/product lifecycle rather than online-service availability.
        ListElement { name: "ALL SYSTEMS"; years: "FULL LIBRARY"; mark: "ALL"; collectionName: ""; folder: "all"; accent: "#dce4f2" }
        ListElement { name: "ARCADE"; years: "1971–PRESENT"; mark: "AR"; collectionName: "Arcade"; folder: "arcade"; accent: "#ffb454" }
        ListElement { name: "NINTENDO ENTERTAINMENT SYSTEM"; years: "1983–2003"; mark: "NES"; collectionName: "Nintendo Entertainment System"; folder: "nes"; accent: "#ff5c6c" }
        ListElement { name: "SEGA GENESIS"; years: "1988–1997"; mark: "GEN"; collectionName: "Sega Genesis"; folder: "megadrive"; accent: "#24c7d9" }
        ListElement { name: "GAME BOY"; years: "1989–2003"; mark: "GB"; collectionName: "Nintendo Game Boy"; folder: "gb"; accent: "#ff5c6c" }
        ListElement { name: "SEGA GAME GEAR"; years: "1990–1997"; mark: "GG"; collectionName: "Sega Game Gear"; folder: "gamegear"; accent: "#24c7d9" }
        ListElement { name: "SUPER NINTENDO"; years: "1990–2003"; mark: "SNES"; collectionName: "Super Nintendo Entertainment System"; folder: "snes"; accent: "#ff5c6c" }
        ListElement { name: "PLAYSTATION"; years: "1994–2006"; mark: "PS1"; collectionName: "Sony PlayStation"; folder: "psx"; accent: "#6f8cff" }
        ListElement { name: "NINTENDO 64"; years: "1996–2002"; mark: "N64"; collectionName: "Nintendo 64"; folder: "n64"; accent: "#ff5c6c" }
        ListElement { name: "SEGA DREAMCAST"; years: "1998–2001"; mark: "DC"; collectionName: "Sega Dreamcast"; folder: "dreamcast"; accent: "#24c7d9" }
        ListElement { name: "GAME BOY COLOR"; years: "1998–2003"; mark: "GBC"; collectionName: "Nintendo Game Boy Color"; folder: "gbc"; accent: "#ff5c6c" }
        ListElement { name: "PLAYSTATION 2"; years: "2000–2013"; mark: "PS2"; collectionName: "Sony PlayStation 2"; folder: "ps2"; accent: "#6f8cff" }
        ListElement { name: "GAME BOY ADVANCE"; years: "2001–2010"; mark: "GBA"; collectionName: "Nintendo Game Boy Advance"; folder: "gba"; accent: "#ff5c6c" }
        ListElement { name: "NINTENDO GAMECUBE"; years: "2001–2007"; mark: "GC"; collectionName: "Nintendo GameCube"; folder: "gc"; accent: "#ff5c6c" }
        ListElement { name: "NINTENDO DS"; years: "2004–2014"; mark: "NDS"; collectionName: "Nintendo DS"; folder: "nds"; accent: "#ff5c6c" }
        ListElement { name: "PLAYSTATION PORTABLE"; years: "2004–2014"; mark: "PSP"; collectionName: "Sony PSP"; folder: "psp"; accent: "#6f8cff" }
        ListElement { name: "PLAYSTATION 3"; years: "2006–2017"; mark: "PS3"; collectionName: "Sony PlayStation 3"; folder: "ps3"; accent: "#6f8cff" }
        ListElement { name: "NINTENDO WII"; years: "2006–2017"; mark: "WII"; collectionName: "Nintendo Wii"; folder: "wii"; accent: "#ff5c6c" }
        ListElement { name: "NINTENDO 3DS"; years: "2011–2020"; mark: "3DS"; collectionName: "Nintendo 3DS"; folder: "n3ds"; accent: "#ff5c6c" }
        ListElement { name: "PLAYSTATION VITA"; years: "2011–2019"; mark: "VITA"; collectionName: "Sony PlayStation Vita"; folder: "psvita"; accent: "#6f8cff" }
        ListElement { name: "NINTENDO WII U"; years: "2012–2017"; mark: "WIIU"; collectionName: "Nintendo Wii U"; folder: "wiiu"; accent: "#ff5c6c" }
        ListElement { name: "WINDOWS 10"; years: "2015–2025"; mark: "WIN"; collectionName: "Windows 10"; folder: "windows"; accent: "#54d98c" }
        ListElement { name: "NINTENDO SWITCH"; years: "2017–PRESENT"; mark: "NSW"; collectionName: "Nintendo Switch"; folder: "switch"; accent: "#ff5c6c" }
    }

    // Predecode official platform logotypes used by the rail. Full-resolution system
    // wallpapers are held by the persistent layers below.
    Repeater {
        model: systemModel.count
        Item {
            x: -2000
            y: -2000
            width: 1
            height: 1
            opacity: 0

            Image {
                source: systemModel.get(index).folder === "all" ? "" :
                        Qt.resolvedUrl("assets/logos-png/" +
                                       systemModel.get(index).folder + ".png")
                asynchronous: true
                cache: true
                sourceSize.width: 700
                sourceSize.height: 300
            }
        }
    }

    SortFilterProxyModel {
        id: recentModel
        sourceModel: api.allGames
        sorters: RoleSorter { roleName: "lastPlayed"; sortOrder: Qt.DescendingOrder }
        filters: RangeFilter { roleName: "playCount"; minimumValue: 1 }
    }

    SortFilterProxyModel {
        id: mostPlayedModel
        sourceModel: api.allGames
        sorters: [
            RoleSorter { roleName: "playCount"; sortOrder: Qt.DescendingOrder },
            RoleSorter { roleName: "lastPlayed"; sortOrder: Qt.DescendingOrder }
        ]
        filters: RangeFilter { roleName: "playCount"; minimumValue: 1 }
    }

    SortFilterProxyModel {
        id: topGamesModel
        sourceModel: api.allGames
        // Native critic score first; the persistent ascending user-score key
        // breaks ties without a JavaScript comparator.
        sorters: [
            RoleSorter { roleName: "rating"; sortOrder: Qt.DescendingOrder },
            RoleSorter { roleName: "sortBy"; sortOrder: Qt.AscendingOrder }
        ]
        filters: RangeFilter { roleName: "rating"; minimumValue: 0.01 }
    }

    SortFilterProxyModel {
        id: gameSortModel
        sourceModel: root.allSystemsActive ? api.allGames :
                     (root.activeCollection ? root.activeCollection.games : null)
        filters: RegExpFilter {
            roleName: "title"
            pattern: root.searchQuery === "" ? ".*" :
                     root.escapedSearchPattern(root.searchQuery)
            caseSensitivity: Qt.CaseInsensitive
        }
        // All four paths stay inside the native proxy model. Critic uses the
        // metadata rating role; user uses a persistent score key in sortBy.
        // This removes thousands of interpreted JS comparisons on entry.
        sorters: RoleSorter {
            roleName: root.sortMode === "critic" ? "rating" :
                      root.sortMode === "user" ? "sortBy" :
                      root.sortMode === "release" ? "release" : "title"
            sortOrder: root.sortMode === "critic" || root.sortMode === "release" ?
                       Qt.DescendingOrder : Qt.AscendingOrder
        }
    }

    Component {
        id: homeShelfCard

        Item {
            id: shelfCard
            property var game: modelData
            property bool isSelected: ListView.isCurrentItem &&
                    root.homeZone === ListView.view.zone
            property real coverAspect: shelfCover.status === Image.Ready &&
                    shelfCover.sourceSize.height > 0 ?
                    shelfCover.sourceSize.width / shelfCover.sourceSize.height : 0.72
            property real coverWidth: Math.min(220, 250 * coverAspect)
            property real coverHeight: coverWidth / coverAspect
            width: 238
            height: 356
            scale: isSelected ? 1.0 : 0.94
            opacity: isSelected ? 1.0 : 0.84
            Behavior on scale { NumberAnimation { duration: 180; easing.type: Easing.OutCubic } }
            Behavior on opacity { NumberAnimation { duration: 150 } }

            Rectangle {
                x: shelfCover.x - 7
                y: shelfCover.y - 7
                width: shelfCover.width + 14
                height: shelfCover.height + 14
                radius: 7
                color: shelfCard.isSelected ?
                       Qt.darker(root.accentForGame(game), 4.5) : "transparent"
                border.width: shelfCard.isSelected ? 5 : 0
                border.color: root.accentForGame(game)
            }

            Image {
                id: shelfCover
                x: (parent.width - parent.coverWidth) / 2
                y: 4 + (250 - parent.coverHeight) / 2
                width: parent.coverWidth
                height: parent.coverHeight
                source: game ? (game.assets.boxFront || "") : ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: true
                smooth: true
                mipmap: true
            }

            Text {
                x: 8
                y: 270
                width: parent.width - 16
                height: 48
                text: game ? game.title : ""
                color: "#eef1f7"
                elide: Text.ElideRight
                wrapMode: Text.Wrap
                maximumLineCount: 2
                font.family: global.fonts.sans
                font.pixelSize: 18
                font.weight: Font.Bold
                style: Text.Outline
                styleColor: "#d0000000"
            }

            Text {
                x: 8
                y: 322
                width: parent.width - 16
                height: 34
                text: root.scoreText(game)
                color: root.accentForGame(game)
                wrapMode: Text.Wrap
                maximumLineCount: 2
                elide: Text.ElideRight
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.weight: Font.Bold
                font.letterSpacing: 0.3
                style: Text.Outline
                styleColor: "#d0000000"
            }

            MouseArea {
                anchors.fill: parent
                onClicked: {
                    ListView.view.currentIndex = index
                    root.focusHomeZone(ListView.view.zone)
                    root.launch(game)
                }
            }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#07090d"
    }

    // The home backdrop is code-native: no generated image can leak back in.
    // Real product photography sits above this restrained accent field.
    Rectangle {
        anchors.fill: parent
        visible: root.page === "home" && root.homeZone === 0
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#07090d" }
            GradientStop { position: 0.58; color: "#0a0e15" }
            GradientStop { position: 1.0; color: Qt.darker(root.accent, 4.2) }
        }
    }

    LinearGradient {
        anchors.fill: parent
        visible: root.page === "home" && root.homeZone === 0
        start: Qt.point(0, height * 0.50)
        end: Qt.point(width, height * 0.50)
        gradient: Gradient {
            GradientStop { position: 0.0; color: "transparent" }
            GradientStop {
                position: 0.38
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.04)
            }
            GradientStop {
                position: 0.70
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.15)
            }
            GradientStop {
                position: 1.0
                color: Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.28)
            }
        }
    }

    // Each system owns a persistent, decoded layer. Its real product photo is
    // rerolled only after that system becomes invisible, so returning to it is
    // instantaneous and never reveals the previous image first. The accent
    // field deliberately reaches far beyond the product instead of collapsing
    // into a tight halo on an otherwise black wallpaper.
    Repeater {
        model: systemModel.count
        Item {
            x: 0
            y: 0
            width: 1920
            height: 1080
            opacity: root.page === "home" && root.homeZone === 0 &&
                     systemRail.currentIndex === index ? 1.0 : 0

            RadialGradient {
                x: 360
                y: -340
                width: 1920
                height: 1480
                horizontalRadius: width * 0.50
                verticalRadius: height * 0.50
                gradient: Gradient {
                    GradientStop {
                        position: 0.0
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.58)
                    }
                    GradientStop {
                        position: 0.28
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.39)
                    }
                    GradientStop {
                        position: 0.58
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.22)
                    }
                    GradientStop {
                        position: 0.82
                        color: Qt.rgba(systemModel.get(index).accent.r,
                                       systemModel.get(index).accent.g,
                                       systemModel.get(index).accent.b, 0.11)
                    }
                    GradientStop { position: 1.0; color: "transparent" }
                }
            }

            Image {
                id: systemHardwareImage
                x: 1010
                y: 88
                width: 850
                height: 350
                source: root.hardwarePhotoBySystem[index] || ""
                fillMode: Image.PreserveAspectFit
                asynchronous: true
                cache: true
                sourceSize.width: 838
                sourceSize.height: 338
            }
        }
    }

    // Two-buffer artwork swap. The old game remains visible until the new
    // image reports Ready; the two invisible loaders keep both D-pad
    // destinations decoded in advance. This also covers sort changes.
    Item {
        anchors.fill: parent
        visible: !(root.page === "home" && root.homeZone === 0)

        Image {
            id: upperArtworkA
            anchors.fill: parent
            fillMode: Image.PreserveAspectCrop
            // Neighbor art is decoded by the preloaders below. Make the
            // cache-hit promotion synchronous so title, row, and wallpaper
            // all change in the same selection event.
            asynchronous: false
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: root.upperArtworkSlot === 0 ? 1.0 : 0.0
            onStatusChanged: if (status === Image.Ready) root.promoteUpperArtwork(0)
        }

        Image {
            id: upperArtworkB
            anchors.fill: parent
            fillMode: Image.PreserveAspectCrop
            asynchronous: false
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: root.upperArtworkSlot === 1 ? 1.0 : 0.0
            onStatusChanged: if (status === Image.Ready) root.promoteUpperArtwork(1)
        }

        Image {
            id: upperArtworkPreloadPrevious
            x: -4
            y: -4
            width: 2
            height: 2
            asynchronous: true
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: 0
        }

        Image {
            id: upperArtworkPreloadNext
            x: -4
            y: -4
            width: 2
            height: 2
            asynchronous: true
            cache: true
            sourceSize.width: 1920
            sourceSize.height: 1080
            opacity: 0
        }
    }

    Item {
        anchors.fill: parent
        visible: !(root.page === "home" && root.homeZone === 0) &&
                 (!root.activeGame || !root.artwork(root.activeGame))

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            y: 130
            text: systemModel.get(root.displaySystemIndex).mark
            color: root.accent
            opacity: 0.13
            font.family: global.fonts.condensed
            font.pixelSize: 430
            font.weight: Font.Bold
        }

        Text {
            anchors.horizontalCenter: parent.horizontalCenter
            y: 650
            width: 1380
            text: root.activeGame ? root.activeGame.title : systemModel.get(root.displaySystemIndex).name
            color: "#dce2ee"
            opacity: 0.22
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            maximumLineCount: 2
            font.family: global.fonts.condensed
            font.pixelSize: 74
            font.weight: Font.DemiBold
        }
    }

    // Preserve the changing light and texture in the upper half of each
    // wallpaper while fully burying its original low-positioned hardware
    // beneath the navigation and card contrast field.
    Rectangle {
        x: 0
        y: 286
        width: parent.width
        height: parent.height - y
        visible: root.page === "home" && root.homeZone === 0
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#1207090d" }
            GradientStop { position: 0.18; color: "#b807090d" }
            GradientStop { position: 0.50; color: "#f207090d" }
            GradientStop { position: 1.0; color: "#ff07090d" }
        }
    }

    QtObject {
        id: previewA
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    QtObject {
        id: previewB
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    QtObject {
        id: previewC
        property string previewMode: ""
        property int systemIndex: -1
        property int gameIndex: -1
        property var previewGame: null
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#99070a10" }
            GradientStop { position: 0.42; color: "#35070a10" }
            GradientStop { position: 0.72; color: "#d9070a10" }
            GradientStop { position: 1.0; color: "#ff07090d" }
        }
    }

    Rectangle {
        anchors.fill: parent
        color: "#28000000"
    }

    // A quiet technical grid gives empty systems a deliberate visual state.
    Repeater {
        model: 12
        Rectangle {
            x: index * root.width / 11
            width: 1
            height: root.height
            color: "#12ffffff"
        }
    }
    Repeater {
        model: 7
        Rectangle {
            y: index * root.height / 6
            width: root.width
            height: 1
            color: "#0dffffff"
        }
    }

    // Fallback for conventional one-display Android handhelds. On the Thor
    // this entire PIP and its decoders remain disabled; the native player owns
    // the physical lower screen instead.
    Item {
        id: singleScreenPip
        z: 80
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.topMargin: 112
        anchors.rightMargin: 56
        width: 520
        height: 292
        visible: root.previewPlacementMode !== "off" &&
                 !root.useBottomPreview() && root.singleCurrentSlot >= 0
        clip: true

        Rectangle {
            anchors.fill: parent
            color: "#e8070a0f"
            border.width: 2
            border.color: root.accent
            radius: 8
        }

        Image {
            anchors.fill: parent
            anchors.margins: 3
            source: root.artwork(root.activeGame)
            fillMode: Image.PreserveAspectCrop
            asynchronous: true
            cache: true
        }

        Video {
            id: singleVideoA
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceA
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 0
            loops: MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 0 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(0)
        }

        Video {
            id: singleVideoB
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceB
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 1
            loops: MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 1 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(1)
        }

        Video {
            id: singleVideoC
            anchors.fill: parent
            anchors.margins: 3
            source: root.singleSourceC
            fillMode: VideoOutput.PreserveAspectCrop
            muted: !root.previewSoundEnabled || root.singleCurrentSlot !== 2
            loops: MediaPlayer.Infinite
            autoPlay: source !== ""
            opacity: root.singleCurrentSlot === 2 ? 1 : 0
            Behavior on opacity { NumberAnimation { duration: 85 } }
            onPositionChanged: if (position > 0) root.promoteSingleVideo(2)
        }
    }

    Item {
        id: chrome
        anchors.fill: parent

        Item {
            id: topBar
            x: 56
            y: 34
            width: parent.width - 112
            height: 58

            Item {
                x: 0
                anchors.verticalCenter: parent.verticalCenter
                width: 230
                height: 64
                property bool leftAnchoredMark:
                        root.brandSlugForSystem(root.displaySystemIndex) === "microsoft"
                visible: root.brandSlugForSystem(root.displaySystemIndex) !== "" &&
                         root.displaySystemIndex > 0

                Image {
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.leftAnchoredMark ? 64 : parent.width
                    height: parent.height
                    source: root.brandLogoForSystem(root.displaySystemIndex)
                    fillMode: Image.PreserveAspectFit
                    horizontalAlignment: Image.AlignLeft
                    asynchronous: true
                    cache: true
                    sourceSize.width: 760
                    sourceSize.height: 224
                }
            }

            Row {
                x: 0
                anchors.verticalCenter: parent.verticalCenter
                height: 58
                spacing: 22
                visible: root.displaySystemIndex === 0 && root.page === "home" &&
                         root.homeZone === 0

                Repeater {
                    model: ["arcade", "nintendo", "sony", "sega", "microsoft"]

                    Item {
                        width: modelData === "arcade" ? 122 :
                               (modelData === "sony" ? 130 :
                               (modelData === "microsoft" ? 58 : 160))
                        height: 58

                        Image {
                            anchors.fill: parent
                            anchors.margins: 0
                            source: modelData === "arcade" ?
                                    Qt.resolvedUrl("assets/logos-png/arcade.png") :
                                    Qt.resolvedUrl("assets/brands/" + modelData + ".png")
                            fillMode: Image.PreserveAspectFit
                            horizontalAlignment: Image.AlignLeft
                            asynchronous: true
                            cache: true
                            smooth: true
                            mipmap: true
                        }
                    }
                }
            }

            Rectangle {
                id: searchControl
                anchors.right: clockStatus.left
                anchors.rightMargin: 22
                anchors.verticalCenter: parent.verticalCenter
                width: root.searchOpen ? 520 : 46
                height: 46
                color: root.searchOpen ? "#e00a0e16" : "transparent"
                border.width: root.searchOpen ? 1 : 0
                border.color: root.searchOpen ? root.accent : "#42ffffff"
                radius: 23
                clip: true

                Behavior on width {
                    NumberAnimation { duration: 180; easing.type: Easing.OutCubic }
                }

                TextInput {
                    id: searchField
                    x: 20
                    anchors.verticalCenter: parent.verticalCenter
                    width: parent.width - (searchClear.visible ? 142 : 76)
                    visible: root.searchOpen
                    text: ""
                    color: "white"
                    selectionColor: root.accent
                    selectedTextColor: "#05070b"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    clip: true
                    selectByMouse: true
                    inputMethodHints: Qt.ImhNoPredictiveText
                    function syncSearchQuery() {
                        var completeValue = String(text) + String(preeditText || "")
                        if (root.searchQuery === completeValue)
                            return
                        root.searchQuery = completeValue
                        searchChangeCommit.restart()
                    }
                    onTextChanged: syncSearchQuery()
                    onPreeditTextChanged: syncSearchQuery()
                    onAccepted: {
                        // Keep the editor focused until this Enter event has fully
                        // unwound. Otherwise Android can deliver the same event to
                        // the game list as A/Play after the keyboard disappears.
                        root.searchKeyboardAccepting = true
                        Qt.inputMethod.hide()
                        Qt.callLater(function() {
                            searchField.focus = false
                            root.forceActiveFocus()
                            root.searchKeyboardAccepting = false
                        })
                    }
                }

                Text {
                    x: 20
                    anchors.verticalCenter: parent.verticalCenter
                    visible: root.searchOpen && searchField.text.length === 0 &&
                             searchField.preeditText.length === 0
                    text: "SEARCH GAMES"
                    color: "#7f899b"
                    font.family: global.fonts.sans
                    font.pixelSize: 18
                    font.letterSpacing: 1.2
                }

                Item {
                    id: searchClear
                    anchors.right: searchMagnifier.left
                    anchors.rightMargin: 0
                    anchors.verticalCenter: parent.verticalCenter
                    // Keep the restrained 17px glyph, but give it a generous
                    // 58x46 touch target so it is easy to hit on a handheld.
                    width: 58
                    height: 46
                    visible: root.searchOpen && root.searchQuery !== ""

                    Rectangle {
                        anchors.centerIn: parent
                        width: 17
                        height: 2
                        radius: 1
                        rotation: 45
                        color: "#cbd2df"
                    }

                    Rectangle {
                        anchors.centerIn: parent
                        width: 17
                        height: 2
                        radius: 1
                        rotation: -45
                        color: "#cbd2df"
                    }

                    MouseArea {
                        x: -4
                        y: -2
                        width: parent.width + 8
                        height: parent.height + 4
                        onClicked: root.endSearch()
                    }
                }

                Item {
                    id: searchMagnifier
                    anchors.right: parent.right
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    width: 38
                    height: 38

                    Image {
                        anchors.centerIn: parent
                        width: 30
                        height: 30
                        source: Qt.resolvedUrl("assets/search-white.png")
                        fillMode: Image.PreserveAspectFit
                        smooth: true
                        mipmap: true
                        cache: true
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            if (!root.searchOpen)
                                root.beginSearch()
                            else {
                                searchField.forceActiveFocus()
                                Qt.inputMethod.show()
                            }
                        }
                    }
                }
            }

            Connections {
                target: Qt.inputMethod
                onVisibleChanged: {
                    // Closing Gboard by its chevron must hand the D-pad back
                    // to Pegasus even though Android keeps the edit session.
                    if (!root.searchKeyboardAccepting &&
                            !Qt.inputMethod.visible && root.searchOpen &&
                            searchField.activeFocus) {
                        searchField.focus = false
                        root.forceActiveFocus()
                    }
                }
            }

            Text {
                id: clockStatus
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: root.clockText + "     " + (isNaN(api.device.batteryPercent) ? "" : Math.round(api.device.batteryPercent * 100) + "%")
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 20
                font.letterSpacing: 1
            }
        }

        Item {
            id: homePage
            anchors.fill: parent
            opacity: root.page === "home" ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 100 } }

            Text {
                x: 58
                y: 150
                visible: root.homeZone === 0
                text: systemModel.get(systemRail.currentIndex).name
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 66
                font.weight: Font.Bold
                font.letterSpacing: 1
            }

            Text {
                x: 62
                y: 225
                visible: root.homeZone === 0
                text: systemRail.currentIndex === 0 ?
                      api.allGames.count + " TITLES ACROSS EVERY SYSTEM" :
                      (root.selectedCollection ? root.selectedCollection.games.count + " TITLES" : "READY FOR YOUR LIBRARY")
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 18
                font.weight: Font.DemiBold
                font.letterSpacing: 3
            }

            Text {
                x: 62
                y: 267
                width: 780
                visible: root.homeZone === 0
                text: systemRail.currentIndex === 0 ?
                      "ONE LIBRARY  •  EVERY GAME  •  SCORES, RELEASES, AND DIRECT LAUNCH" :
                      (root.selectedCollection ? "SELECT TO BROWSE  •  GAMES LAUNCH DIRECTLY" :
                       "ADD GAMES TO  /GAMES/" + systemModel.get(systemRail.currentIndex).folder)
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 18
                font.letterSpacing: 1
            }

            Text {
                x: 58
                y: 148
                width: parent.width - 116
                visible: root.homeZone > 0
                text: root.activeGame ? root.activeGame.title : "CONTINUE PLAYING"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 60
                minimumPixelSize: 36
                fontSizeMode: Text.HorizontalFit
                font.weight: Font.Bold
                maximumLineCount: 1
            }

            Text {
                x: 62
                y: 224
                visible: root.homeZone > 0
                text: root.gameFactsText(root.activeGame)
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 22
                font.weight: Font.Bold
                font.letterSpacing: 1.1
            }

            Text {
                x: 62
                y: 262
                visible: root.homeZone > 0
                text: root.homeShelfName(root.homeZone) + "     A  PLAY"
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 17
                font.letterSpacing: 2
            }

            ListView {
                id: systemRail
                x: 0
                y: 350
                width: parent.width
                height: 176
                orientation: ListView.Horizontal
                model: systemModel
                spacing: 14
                // The selected delegate grows beyond its nominal height. The
                // rail has ample vertical breathing room, so do not cut its
                // top rim at the ListView boundary.
                clip: false
                // Inline end spacers let the first and last real delegates
                // reach the same centered position as every middle system.
                // Without them, Arcade and Switch stop at the screen edge and
                // their scaled selection rims are physically clipped.
                header: Item {
                    width: Math.max(0, (systemRail.width - 230) / 2)
                    height: systemRail.height
                }
                footer: Item {
                    width: Math.max(0, (systemRail.width - 230) / 2)
                    height: systemRail.height
                }
                focus: root.homeZone === 0
                // Keep the selected platform centered, but visibly translate
                // the entire rail in the direction of travel. The short,
                // retargetable duration preserves rapid D-pad response while
                // making every one-step system change spatially legible.
                highlightMoveDuration: 165
                highlightRangeMode: ListView.ApplyRange
                // The rail spans the full display. The preferred position is
                // the selected delegate's leading edge, so offset by half its
                // width to center it on the physical screen precisely.
                preferredHighlightBegin: (width - 230) / 2
                preferredHighlightEnd: (width - 230) / 2
                keyNavigationWraps: true
                keyNavigationEnabled: false
                onCurrentIndexChanged: {
                    if (root.previewReady) {
                        if (root.page === "home" && root.homeZone === 0) {
                            // Keep the D-pad event turn identical to gameRail:
                            // update selection now, coalesce expensive visual
                            // and preview work immediately afterward.
                            // The destination layer is already decoded. Swap
                            // immediately, then reroll only the departed layer.
                            root.chooseSystemWallpaper(systemRail.currentIndex)
                            systemChangeCommit.restart()
                        }
                    }
                }

                delegate: Item {
                    id: systemCard
                    property bool isSelected: ListView.isCurrentItem && root.homeZone === 0
                    width: 230
                    height: 160
                    scale: isSelected ? 1.10 : 0.88
                    opacity: isSelected ? 1.0 : 0.46
                    Behavior on scale { NumberAnimation { duration: 145; easing.type: Easing.OutCubic } }
                    Behavior on opacity { NumberAnimation { duration: 125 } }

                    Rectangle {
                        anchors.fill: parent
                        color: systemCard.isSelected ?
                               Qt.darker(model.accent, 4.5) : "#9b11141b"
                        border.width: systemCard.isSelected ? 5 : 1
                        border.color: systemCard.isSelected ? model.accent : "#42ffffff"
                        radius: 7
                    }

                    Rectangle {
                        x: 18
                        y: 18
                        width: 46
                        height: systemCard.isSelected ? 7 : 4
                        color: model.accent
                    }

                    Image {
                        x: 12
                        // Vertically center the wordmark in the clear space
                        // between the accent stroke and the year label.
                        y: 30
                        width: parent.width - 24
                        height: 96
                        source: model.folder === "all" ? "" :
                                Qt.resolvedUrl("assets/logos-png/" + model.folder + ".png")
                        visible: model.folder !== "all"
                        fillMode: Image.PreserveAspectFit
                        horizontalAlignment: model.folder === "windows" ?
                                Image.AlignLeft : Image.AlignHCenter
                        asynchronous: true
                        cache: true
                        smooth: true
                        mipmap: true
                        sourceSize.width: 840
                        sourceSize.height: 360
                    }

                    Text {
                        x: 12
                        y: 34
                        width: parent.width - 24
                        height: 88
                        visible: model.folder === "all"
                        text: "ALL\nSYSTEMS"
                        color: "#f4f7fc"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                        lineHeight: 0.82
                        font.family: global.fonts.condensed
                        font.pixelSize: 38
                        font.weight: Font.Black
                        font.letterSpacing: 2
                    }

                    Text {
                        x: 18
                        y: 134
                        width: parent.width - 34
                        text: model.years
                        color: model.accent
                        font.family: global.fonts.sans
                        font.pixelSize: 12
                        font.weight: Font.DemiBold
                        font.letterSpacing: 2
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            systemRail.currentIndex = index
                            root.homeZone = 0
                            root.enterCollection()
                        }
                    }
                }
            }

            Rectangle {
                x: 58
                y: 580
                width: parent.width - 116
                height: 1
                color: "#35ffffff"
            }

            Item {
                id: shelfViewport
                x: 0
                y: 594
                width: parent.width
                height: 446
                clip: true

                Item {
                    id: shelfStack
                    width: parent.width
                    height: 1320
                    y: -Math.max(0, root.homeZone - 1) * 440
                    Behavior on y { NumberAnimation { duration: 210; easing.type: Easing.OutCubic } }

                    Item {
                        y: 0
                        width: parent.width
                        height: 440

                        Text {
                            x: 58
                            y: 16
                            text: "CONTINUE PLAYING"
                            color: root.homeZone === 1 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: recentRail
                            property int zone: 1
                            x: 0
                            y: 66
                            width: parent.width
                            height: 360
                            orientation: ListView.Horizontal
                            model: recentModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            header: Item { width: Math.max(0, (recentRail.width - 238) / 2); height: recentRail.height }
                            footer: Item { width: Math.max(0, (recentRail.width - 238) / 2); height: recentRail.height }
                            focus: root.homeZone === 1
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - 238) / 2
                            preferredHighlightEnd: (width - 238) / 2
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 1) root.activateShelfPreview(1)
                        }
                    }

                    Item {
                        y: 440
                        width: parent.width
                        height: 440

                        Text {
                            x: 58
                            y: 16
                            text: "MOST PLAYED"
                            color: root.homeZone === 2 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: mostPlayedRail
                            property int zone: 2
                            x: 0
                            y: 66
                            width: parent.width
                            height: 360
                            orientation: ListView.Horizontal
                            model: mostPlayedModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            header: Item { width: Math.max(0, (mostPlayedRail.width - 238) / 2); height: mostPlayedRail.height }
                            footer: Item { width: Math.max(0, (mostPlayedRail.width - 238) / 2); height: mostPlayedRail.height }
                            focus: root.homeZone === 2
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - 238) / 2
                            preferredHighlightEnd: (width - 238) / 2
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 2) root.activateShelfPreview(2)
                        }
                    }

                    Item {
                        y: 880
                        width: parent.width
                        height: 440

                        Text {
                            x: 58
                            y: 16
                            text: "TOP GAMES"
                            color: root.homeZone === 3 ? "white" : "#8f98aa"
                            font.family: global.fonts.sans
                            font.pixelSize: 18
                            font.weight: Font.DemiBold
                            font.letterSpacing: 3
                        }

                        ListView {
                            id: topGamesRail
                            property int zone: 3
                            x: 0
                            y: 66
                            width: parent.width
                            height: 360
                            orientation: ListView.Horizontal
                            model: topGamesModel
                            delegate: homeShelfCard
                            spacing: 18
                            clip: false
                            header: Item { width: Math.max(0, (topGamesRail.width - 238) / 2); height: topGamesRail.height }
                            footer: Item { width: Math.max(0, (topGamesRail.width - 238) / 2); height: topGamesRail.height }
                            focus: root.homeZone === 3
                            highlightMoveDuration: 180
                            highlightRangeMode: ListView.ApplyRange
                            preferredHighlightBegin: (width - 238) / 2
                            preferredHighlightEnd: (width - 238) / 2
                            keyNavigationWraps: true
                            keyNavigationEnabled: false
                            onCurrentIndexChanged: if (root.previewReady && root.page === "home" && root.homeZone === 3) root.activateShelfPreview(3)
                        }
                    }
                }
            }
        }

        Item {
            id: gamesPage
            anchors.fill: parent
            opacity: root.page === "games" ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: 100 } }

            Text {
                x: 58
                y: 142
                text: systemModel.get(root.displaySystemIndex).name
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 22
                font.weight: Font.DemiBold
                font.letterSpacing: 3
            }

            Text {
                x: 58
                y: 180
                width: parent.width - 116
                height: 68
                text: root.activeGame ? root.activeGame.title :
                      (root.searchQuery !== "" ? "NO MATCHING GAMES" : "NO GAMES YET")
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 58
                minimumPixelSize: 34
                fontSizeMode: Text.HorizontalFit
                font.weight: Font.Bold
                maximumLineCount: 1
            }

            Text {
                x: 62
                y: 253
                text: root.gameFactsText(root.activeGame)
                color: root.accent
                font.family: global.fonts.sans
                font.pixelSize: 22
                font.weight: Font.Bold
                font.letterSpacing: 1.1
            }

            Text {
                x: 62
                y: 286
                text: (root.allSystemsActive || root.activeCollection) ?
                      (gameSortModel.count > 0 ? gameRail.currentIndex + 1 : 0) +
                      " / " + gameSortModel.count +
                      (root.allSystemsActive ? "     ALL SYSTEMS  •  SELECT TO PLAY" : "     SELECT TO PLAY") :
                      "ADD GAMES TO  /GAMES/" + systemModel.get(root.activeSystemIndex).folder
                color: "#aeb6c8"
                font.family: global.fonts.sans
                font.pixelSize: 17
                font.letterSpacing: 2
            }

            Rectangle {
                x: 58
                y: 326
                width: 460
                height: 2
                color: root.accent
            }

            Row {
                x: 48
                y: 350
                spacing: 8

                Repeater {
                    model: ["critic", "user", "alpha", "release"]
                    Rectangle {
                        width: 142
                        height: 44
                        color: "transparent"
                        clip: true
                        border.width: 1
                        border.color: root.sortMode === modelData ?
                                      Qt.lighter(root.accent, 1.18) : "#38ffffff"
                        radius: 7

                        ShaderEffectSource {
                            id: sortGlassSource
                            anchors.fill: parent
                            sourceItem: root.upperArtworkSlot === 0 ? upperArtworkA : upperArtworkB
                            sourceRect: Qt.rect(48 + index * 150 - 16, 350 - 16,
                                                142 + 32, 44 + 32)
                            textureSize: Qt.size(142 + 32, 44 + 32)
                            live: true
                            smooth: true
                            visible: false
                        }

                        Rectangle {
                            anchors.fill: parent
                            visible: !root.liquidGlassEnabled
                            color: root.sortMode === modelData ?
                                   Qt.rgba(root.accent.r, root.accent.g,
                                           root.accent.b, 0.90) : "#76060a10"
                            radius: 7
                        }

                        ShaderEffect {
                            anchors.fill: parent
                            visible: root.liquidGlassEnabled
                            property variant source: sortGlassSource
                            property size glassSize: Qt.size(width, height)
                            property real cornerRadius: 7
                            property real edgeThickness: 14
                            property real distortionStrength: 11.0
                            property real scatterRadius: 2.8
                            property real samplePadding: 16
                            property color glassTint: root.sortMode === modelData ?
                                Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.80) :
                                Qt.rgba(0.025, 0.04, 0.07, 0.38)
                            fragmentShader: root.liquidGlassFragmentShader
                        }

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: 2
                            color: "transparent"
                            border.width: 1
                            border.color: root.sortMode === modelData ?
                                          "#42ffffff" : "#20ffffff"
                            radius: 5
                        }

                        Text {
                            anchors.centerIn: parent
                            text: root.sortLabel(modelData)
                            color: root.sortMode === modelData ? "#071016" : "#e3e7ef"
                            font.family: global.fonts.sans
                            font.pixelSize: 15
                            font.weight: Font.Bold
                            font.letterSpacing: 0.55
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                root.sortMode = modelData
                                api.memory.set("thoriumSortMode", root.sortMode)
                                gameRail.currentIndex = 0
                                sortChangeCommit.restart()
                            }
                        }
                    }
                }
            }

            ListView {
                id: gameRail
                x: 48
                y: 450
                width: parent.width - 48
                height: 560
                orientation: ListView.Horizontal
                model: gameSortModel
                spacing: 22
                clip: false
                focus: root.page === "games"
                highlightMoveDuration: 230
                highlightRangeMode: ListView.ApplyRange
                preferredHighlightBegin: (width - 250) / 2
                preferredHighlightEnd: (width - 250) / 2
                keyNavigationWraps: true
                keyNavigationEnabled: false
                visible: root.gameViewMode === "covers"
                onCurrentIndexChanged: {
                    if (root.gameViewMode === "list")
                        gameListRail.positionViewAtIndex(currentIndex, ListView.Contain)
                    if (root.previewReady && root.page === "games") {
                        root.activateGamePreview()
                    }
                }

                delegate: Item {
                    id: gameCard
                    property bool isSelected: ListView.isCurrentItem
                    property var game: modelData
                    property real coverAspect: gameCover.status === Image.Ready &&
                            gameCover.sourceSize.height > 0 ?
                            gameCover.sourceSize.width / gameCover.sourceSize.height : 0.72
                    property real boxWidth: Math.min(226, 318 * coverAspect)
                    property real boxHeight: boxWidth / coverAspect
                    width: 250
                    height: 478
                    scale: ListView.isCurrentItem ? 1.08 : 0.91
                    opacity: ListView.isCurrentItem ? 1.0 : 0.82
                    Behavior on scale { NumberAnimation { duration: 230; easing.type: Easing.OutCubic } }
                    Behavior on opacity { NumberAnimation { duration: 180 } }

                    Image {
                        id: gameCover
                        x: (parent.width - parent.boxWidth) / 2
                        y: 12 + (318 - parent.boxHeight) / 2
                        width: parent.boxWidth
                        height: parent.boxHeight
                        source: game ? (game.assets.boxFront || "") : ""
                        fillMode: Image.PreserveAspectFit
                        asynchronous: true
                        smooth: true
                        mipmap: true
                    }

                    Rectangle {
                        x: 12
                        y: 338
                        width: 36
                        height: 3
                        color: root.accent
                    }

                    Text {
                        x: 12
                        y: 354
                        width: parent.width - 24
                        height: 46
                        text: game ? game.title : ""
                        color: "#f1f3f8"
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                        font.family: global.fonts.sans
                        font.pixelSize: gameCard.isSelected ? 22 : 19
                        font.weight: Font.Bold
                        style: Text.Outline
                        styleColor: "#d0000000"
                    }

                    Text {
                        x: 12
                        y: 416
                        width: parent.width - 24
                        height: 36
                        text: root.scoreText(game) + "\nRELEASE  " + root.releaseYear(game)
                        color: root.accent
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                        font.family: global.fonts.sans
                        font.pixelSize: 17
                        font.weight: Font.Bold
                        font.letterSpacing: 0.2
                        style: Text.Outline
                        styleColor: "#d0000000"
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: {
                            gameRail.currentIndex = index
                            root.launch(game)
                        }
                    }
                }
            }

            Item {
                id: listViewPanel
                x: 48
                y: 324
                width: parent.width - 96
                // Nine exact 76px rows plus eight 4px gaps. Keeping the
                // viewport on the 80px row cadence prevents a fractional row
                // from being clipped at the top once selection is centered.
                height: 716
                visible: root.gameViewMode === "list"

                Item {
                    id: selectedGameArtPanel
                    x: 0
                    y: 106
                    width: 600
                    height: parent.height - 106
                    property real coverAspect: listCover.status === Image.Ready &&
                            listCover.sourceSize.height > 0 ?
                            listCover.sourceSize.width / listCover.sourceSize.height : 0.72
                    property real coverWidth: Math.min(520, 510 * coverAspect)
                    property real coverHeight: coverWidth / coverAspect

                    Image {
                        id: listCover
                        anchors.centerIn: parent
                        width: parent.coverWidth
                        height: parent.coverHeight
                        source: root.activeGame ?
                                (root.activeGame.assets.boxFront || "") : ""
                        fillMode: Image.PreserveAspectFit
                        asynchronous: true
                        smooth: true
                    }

                    Text {
                        anchors.centerIn: parent
                        width: parent.width - 80
                        visible: root.activeGame && !root.activeGame.assets.boxFront
                        text: root.activeGame ? root.activeGame.title : ""
                        color: "#e8ebf2"
                        horizontalAlignment: Text.AlignHCenter
                        wrapMode: Text.Wrap
                        font.family: global.fonts.condensed
                        font.pixelSize: 44
                        font.weight: Font.DemiBold
                    }
                }

                ListView {
                    id: gameListRail
                    x: 634
                    y: 0
                    width: parent.width - x
                    height: parent.height
                    orientation: ListView.Vertical
                    model: gameSortModel
                    currentIndex: gameRail.currentIndex
                    spacing: 4
                    clip: true
                    focus: false
                    keyNavigationEnabled: false
                    highlightMoveDuration: 100
                    highlightRangeMode: ListView.ApplyRange
                    preferredHighlightBegin: (height - 76) / 2
                    preferredHighlightEnd: (height - 76) / 2

                    delegate: Rectangle {
                        id: gameListRow
                        property bool isSelected: ListView.isCurrentItem
                        property var game: modelData
                        width: gameListRail.width
                        height: 76
                        color: "transparent"
                        clip: true
                        border.width: ListView.isCurrentItem ? 2 : 1
                        border.color: ListView.isCurrentItem ?
                                      Qt.lighter(root.accent, 1.18) : "#38ffffff"
                        radius: 7

                        ShaderEffectSource {
                            id: rowGlassSource
                            anchors.fill: parent
                            sourceItem: root.upperArtworkSlot === 0 ? upperArtworkA : upperArtworkB
                            sourceRect: Qt.rect(listViewPanel.x + gameListRail.x - 28,
                                                listViewPanel.y + gameListRow.y -
                                                gameListRail.contentY - 28,
                                                gameListRow.width + 56,
                                                gameListRow.height + 56)
                            textureSize: Qt.size(gameListRow.width + 56,
                                                 gameListRow.height + 56)
                            live: true
                            smooth: true
                            visible: false
                        }

                        Rectangle {
                            anchors.fill: parent
                            visible: !root.liquidGlassEnabled
                            color: gameListRow.isSelected ?
                                   Qt.rgba(root.accent.r, root.accent.g,
                                           root.accent.b, 0.92) :
                                   (index % 2 === 0 ? "#78060a10" : "#86060a10")
                            radius: 7
                        }

                        ShaderEffect {
                            anchors.fill: parent
                            visible: root.liquidGlassEnabled
                            property variant source: rowGlassSource
                            property size glassSize: Qt.size(width, height)
                            property real cornerRadius: 7
                            property real edgeThickness: 23
                            property real distortionStrength: 20.0
                            property real scatterRadius: 4.2
                            property real samplePadding: 28
                            property color glassTint: gameListRow.isSelected ?
                                Qt.rgba(root.accent.r, root.accent.g, root.accent.b, 0.86) :
                                (index % 2 === 0 ? Qt.rgba(0.025, 0.04, 0.07, 0.40) :
                                                  Qt.rgba(0.025, 0.04, 0.07, 0.45))
                            fragmentShader: root.liquidGlassFragmentShader
                        }

                        Rectangle {
                            anchors.fill: parent
                            anchors.margins: gameListRow.isSelected ? 2 : 1
                            color: "transparent"
                            border.width: 1
                            border.color: gameListRow.isSelected ? "#46ffffff" : "#20ffffff"
                            radius: 5
                        }

                        Text {
                            x: 20
                            anchors.verticalCenter: parent.verticalCenter
                            width: 54
                            text: (index < 9 ? "0" : "") + (index + 1)
                            color: gameListRow.isSelected ? "#03050a" : "#647087"
                            font.family: global.fonts.condensed
                            font.pixelSize: 19
                            font.weight: Font.Bold
                        }

                        Text {
                            x: 78
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.width - 590
                            text: game ? game.title : ""
                            color: gameListRow.isSelected ? "#03050a" : "#eef1f6"
                            elide: Text.ElideRight
                            font.family: global.fonts.sans
                            font.pixelSize: 24
                            font.weight: gameListRow.isSelected ? Font.Bold : Font.DemiBold
                        }

                        Text {
                            anchors.right: parent.right
                            anchors.rightMargin: 22
                            anchors.verticalCenter: parent.verticalCenter
                            width: 480
                            text: root.gameFactsText(game)
                            color: gameListRow.isSelected ? "#03050a" : root.accent
                            horizontalAlignment: Text.AlignRight
                            elide: Text.ElideRight
                            font.family: global.fonts.sans
                            font.pixelSize: 19
                            font.weight: Font.Bold
                            font.letterSpacing: 0.1
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: {
                                gameRail.currentIndex = index
                                root.launch(game)
                            }
                        }
                    }
                }
            }

            Text {
                anchors.right: parent.right
                anchors.rightMargin: 62
                y: 1040
                text: "L1 / R1  SORT     L2 / R2  SYSTEM     X / Y  VIEW: " + root.gameViewMode.toUpperCase() +
                      "     D-PAD  NAVIGATE     B  BACK     A  PLAY"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1
            }
        }
    }

    Text {
        id: displaySettingsHint
        z: 90
        anchors.right: parent.right
        anchors.rightMargin: 62
        y: 1040
        visible: root.page === "home" && !root.settingsOpen
        text: "X / Y  DISPLAY SETTINGS     D-PAD  NAVIGATE     A  OPEN"
        color: "#7f899c"
        font.family: global.fonts.sans
        font.pixelSize: 15
        font.letterSpacing: 1

        MouseArea {
            anchors.fill: parent
            anchors.margins: -18
            onClicked: {
                root.settingsOpen = true
                root.settingsIndex = 0
            }
        }
    }

    Rectangle {
        id: settingsOverlay
        z: 500
        anchors.fill: parent
        visible: root.settingsOpen
        color: "#e905080d"

        MouseArea { anchors.fill: parent }

        Rectangle {
            anchors.centerIn: parent
            width: 980
            height: 900
            color: "#f20d121a"
            border.width: 1
            border.color: root.accent
            radius: 8

            Text {
                x: 48
                y: 38
                text: "DISPLAY & LIBRARY SETTINGS"
                color: "white"
                font.family: global.fonts.condensed
                font.pixelSize: 42
                font.weight: Font.Bold
                font.letterSpacing: 2
            }

            Text {
                x: 50
                y: 94
                text: "Display changes apply instantly; library updates run only when requested"
                color: "#9da7b8"
                font.family: global.fonts.sans
                font.pixelSize: 17
            }

            Rectangle {
                x: 44
                y: 150
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 0 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 0 ? 2 : 1
                border.color: root.settingsIndex === 0 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "SYSTEM WALLPAPER MODE"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Preloaded static angle; rerolls only after you leave"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: "STATIC"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 0; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 278
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 1 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 1 ? 2 : 1
                border.color: root.settingsIndex === 1 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "GAME PREVIEW PLACEMENT"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Automatic detects the screen count; choose PIP or Off manually"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.previewPlacementLabel()
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 1; root.activateSetting(1) }
                }
            }

            Rectangle {
                x: 44
                y: 406
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 2 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 2 ? 2 : 1
                border.color: root.settingsIndex === 2 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "PREVIEW VIDEO SOUND"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Sound follows the visible preview; preloaded neighbors stay silent"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.previewSoundEnabled ? "ON" : "OFF"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 2; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 534
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 3 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 3 ? 2 : 1
                border.color: root.settingsIndex === 3 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "LIQUID GLASS"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Optional refractive blur and wallpaper distortion for controls"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.liquidGlassEnabled ? "ON" : "OFF"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 3; root.activateSetting(0) }
                }
            }

            Rectangle {
                x: 44
                y: 662
                width: parent.width - 88
                height: 112
                color: root.settingsIndex === 4 ? "#261f2a38" : "#9b111720"
                border.width: root.settingsIndex === 4 ? 2 : 1
                border.color: root.settingsIndex === 4 ? root.accent : "#30ffffff"
                radius: 5

                Text {
                    x: 28
                    y: 22
                    text: "UPDATE GAME LIBRARY"
                    color: "white"
                    font.family: global.fonts.sans
                    font.pixelSize: 21
                    font.weight: Font.Bold
                    font.letterSpacing: 1
                }
                Text {
                    x: 28
                    y: 60
                    text: "Scan Downloads, repair metadata, and add newly verified games"
                    color: "#9da7b8"
                    font.family: global.fonts.sans
                    font.pixelSize: 16
                }
                Text {
                    anchors.right: parent.right
                    anchors.rightMargin: 28
                    anchors.verticalCenter: parent.verticalCenter
                    text: "RUN"
                    color: root.accent
                    font.family: global.fonts.condensed
                    font.pixelSize: 30
                    font.weight: Font.Bold
                }
                MouseArea {
                    anchors.fill: parent
                    onClicked: { root.settingsIndex = 4; root.activateSetting(0) }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                y: 832
                text: "UP / DOWN  SELECT     LEFT / RIGHT  CHANGE     B  CLOSE"
                color: "#7f899c"
                font.family: global.fonts.sans
                font.pixelSize: 15
                font.letterSpacing: 1
            }
        }
    }
}
