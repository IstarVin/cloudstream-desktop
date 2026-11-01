-- Cloudstream Desktop: connects mpv to the cloudstream-desktop server.
--
-- The server starts mpv with this script and talks to it over mpv's JSON IPC:
--   server -> script: `script-message-to cloudstream <cmd> <json>`
--   script -> server: `script-message cs-event <json>` (IPC clients receive it as a client-message event)
-- The server is the source of truth: this script sends intents and shows what the server tells it.

local mp = require "mp"
local utils = require "mp.utils"
local options = require "mp.options"
local input = require "mp.input"

local opts = {
    key_next = "N",
    key_prev = "P",
    key_sources = "S",
    key_episodes = "E",
    key_watched_next = "W",
    osd_duration = 4,
}
options.read_options(opts, "cloudstream")

local function send(event)
    mp.commandv("script-message", "cs-event", utils.format_json(event))
end

local function parse(json)
    return utils.parse_json(json or "{}") or {}
end

local function ass_escape(s)
    return (tostring(s or ""):gsub("\\", "\\\226\129\160"):gsub("{", "\\{"):gsub("\n", "\\N"))
end

-- Loading overlay: shown while the server resolves links, so an idle mpv window never looks frozen.
local overlay = mp.create_osd_overlay("ass-events")
local loading = nil

local function draw_loading()
    if not loading then
        overlay:remove()
        return
    end
    local lines = { "{\\an5\\fs42\\bord2\\b1}" .. ass_escape(loading.status or "Resolving links…") }
    if loading.title then
        local sub = loading.title
        if loading.label then sub = sub .. " · " .. loading.label end
        table.insert(lines, "{\\an5\\fs30\\bord2\\b0}" .. ass_escape(sub))
    end
    overlay.data = table.concat(lines, "\\N")
    overlay:update()
end

local function hide_loading()
    loading = nil
    draw_loading()
end

mp.register_script_message("loading", function(json)
    local d = parse(json)
    loading = { title = d.title, label = d.label, status = "Resolving links…" }
    draw_loading()
end)

mp.register_script_message("loading-done", hide_loading)

mp.register_script_message("item", function(json)
    local d = parse(json)
    hide_loading()
    local first = d.title or ""
    if d.label then first = first .. "\n" .. d.label end
    local meta = {}
    if d.provider then table.insert(meta, d.provider) end
    if d.source then table.insert(meta, d.source) end
    mp.osd_message(first .. "\n" .. table.concat(meta, " · "), opts.osd_duration)
end)

-- Notices such as "Link failed, trying Movy 1080p…". While loading, they replace the overlay's status line.
mp.register_script_message("osd", function(json)
    local d = parse(json)
    if loading then
        loading.status = d.text
        draw_loading()
    else
        mp.osd_message(d.text or "", opts.osd_duration)
    end
end)

mp.register_event("file-loaded", hide_loading)

local function select(prompt, items, on_submit)
    if #items == 0 then
        mp.osd_message("Nothing to pick yet", 2)
        return
    end
    local labels, default = {}, 1
    for i, item in ipairs(items) do
        labels[i] = item.label
        if item.current then default = i end
    end
    input.select({
        prompt = prompt,
        items = labels,
        default_item = default,
        submit = function(i) on_submit(items[i]) end,
    })
end

mp.register_script_message("sources", function(json)
    local d = parse(json)
    local prompt = d.loading and "Source (still resolving):" or "Source:"
    select(prompt, d.items or {}, function(item) send({ type = "source", id = item.id }) end)
end)

mp.register_script_message("episodes", function(json)
    local d = parse(json)
    for _, item in ipairs(d.items or {}) do
        if item.watched then item.label = item.label .. "  ✓" end
    end
    select("Episode:", d.items or {}, function(item) send({ type = "episode", index = item.index }) end)
end)

mp.add_forced_key_binding(opts.key_next, "cs-next", function() send({ type = "next" }) end)
mp.add_forced_key_binding(opts.key_prev, "cs-prev", function() send({ type = "prev" }) end)
mp.add_forced_key_binding(opts.key_sources, "cs-sources", function() send({ type = "sources" }) end)
mp.add_forced_key_binding(opts.key_episodes, "cs-episodes", function() send({ type = "episodes" }) end)
mp.add_forced_key_binding(opts.key_watched_next, "cs-watched-next", function() send({ type = "watched-next" }) end)
