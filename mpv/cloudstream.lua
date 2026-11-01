-- Cloudstream Desktop: connects mpv to the cloudstream-desktop server.
--
-- The server starts mpv with this script and talks to it over mpv's JSON IPC:
--   server -> script: `script-message-to cloudstream <cmd> <json>`
--   script -> server: `script-message cs-event <json>` (IPC clients receive it as a client-message event)
-- The server is the source of truth: this script sends intents and shows what the server tells it.
-- With uosc installed, the pickers open as uosc menus and the script adds uosc buttons; otherwise it uses mp.input.

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
    key_skip = "TAB",
    osd_duration = 4,
    uosc = true,
}
options.read_options(opts, "cloudstream")

local unpack = unpack or table.unpack

local function event_args(event)
    return { "script-message", "cs-event", utils.format_json(event) }
end

local function send(event)
    mp.commandv(unpack(event_args(event)))
end

local function parse(json)
    return utils.parse_json(json or "{}") or {}
end

local function ass_escape(s)
    return (tostring(s or ""):gsub("\\", "\\\226\129\160"):gsub("{", "\\{"):gsub("\n", "\\N"))
end

-- uosc: `script-message-to uosc` fails when uosc isn't loaded, so every call doubles as the presence check.
local uosc = { present = false, menu = nil }

mp.register_script_message("uosc-version", function() uosc.present = true end)

local function to_uosc(...)
    if not opts.uosc then return false end
    local ok = mp.commandv("script-message-to", "uosc", ...)
    if ok then uosc.present = true end
    return ok
end

local function set_button(name, button)
    to_uosc("set-button", name, utils.format_json(button))
end

local function register_buttons()
    set_button("cs_prev", { icon = "skip_previous", tooltip = "Previous episode", command = event_args({ type = "prev" }) })
    set_button("cs_next", { icon = "skip_next", tooltip = "Next episode", command = event_args({ type = "next" }) })
    set_button("cs_sources", { icon = "layers", tooltip = "Sources", command = event_args({ type = "sources" }) })
    set_button("cs_episodes", { icon = "format_list_numbered", tooltip = "Episodes", command = event_args({ type = "episodes" }) })
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
    register_buttons()
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

-- Skip intro/outro: the server sends AniSkip ranges once the file's length is known. They become chapters, and
-- while the position is inside one (in prompt mode) a "Skip" prompt shows with key_skip bound. The server does the
-- seek, so skipping from the web UI or the phone works the same way.
local skips = { ranges = {}, auto = false, shown = nil }
local prompt = mp.create_osd_overlay("ass-events")

local function skip_button(r)
    set_button("cs_skip", {
        icon = "fast_forward",
        tooltip = r and ("Skip " .. r.label:lower()) or "Skip",
        hide = r == nil,
        command = event_args({ type = "skip" }),
    })
end

local function hide_prompt()
    if not skips.shown then return end
    skips.shown = nil
    prompt:remove()
    mp.remove_key_binding("cs-skip")
    skip_button(nil)
end

local function range_at(t)
    for _, r in ipairs(skips.ranges) do
        if not r.skipped and t >= r.start and t < r["end"] - 1 then return r end
    end
    return nil
end

mp.observe_property("time-pos", "number", function(_, t)
    local r = nil
    if t and not skips.auto then r = range_at(t) end
    if r == skips.shown then return end
    hide_prompt()
    if not r then return end
    skips.shown = r
    prompt.data = "{\\an3\\fs30\\bord2\\b1}" .. ass_escape("Skip " .. r.label:lower())
        .. "{\\b0\\fs24}  [" .. ass_escape(opts.key_skip) .. "]"
    prompt:update()
    skip_button(r)
    mp.add_forced_key_binding(opts.key_skip, "cs-skip", function()
        r.skipped = true
        hide_prompt()
        send({ type = "skip" })
    end)
end)

mp.register_script_message("skips", function(json)
    local d = parse(json)
    hide_prompt()
    skips.ranges = d.ranges or {}
    skips.auto = d.auto == true
    local chapters = mp.get_property_native("chapter-list") or {}
    local duration = mp.get_property_number("duration", 0)
    for _, r in ipairs(skips.ranges) do
        table.insert(chapters, { title = r.label, time = r.start })
        -- Mark where the episode resumes, unless another range starts right there.
        local resumes = r["end"] < duration - 1
        for _, o in ipairs(skips.ranges) do
            if math.abs(o.start - r["end"]) < 1 then resumes = false end
        end
        if resumes then table.insert(chapters, { title = "Episode", time = r["end"] }) end
    end
    table.sort(chapters, function(a, b) return a.time < b.time end)
    mp.set_property_native("chapter-list", chapters)
end)

mp.register_event("start-file", function()
    hide_prompt()
    skips.ranges = {}
end)

-- Pickers: a uosc menu when uosc is there, else mp.input.select. Items: {label, title?, hint?, current, event}.
mp.register_script_message("menu-closed", function(menu_type)
    if uosc.menu == menu_type then uosc.menu = nil end
end)

local function pick(menu_type, prompt_text, items, loading_more)
    local menu = {
        type = menu_type,
        title = prompt_text,
        items = {},
        on_close = { "script-message-to", "cloudstream", "menu-closed", menu_type },
    }
    for i, item in ipairs(items) do
        menu.items[i] = { title = item.title or item.label, hint = item.hint, active = item.current, value = event_args(item.event) }
    end
    if loading_more then
        table.insert(menu.items, { title = "Resolving more links…", muted = true, italic = true, selectable = false, value = "ignore" })
    end
    if #menu.items > 0 then
        local json = utils.format_json(menu)
        if uosc.menu == menu_type and to_uosc("update-menu", json) then return end
        if to_uosc("open-menu", json) then
            uosc.menu = menu_type
            return
        end
    end
    if #items == 0 then
        mp.osd_message(loading_more and "Still resolving links…" or "Nothing to pick yet", 2)
        return
    end
    local labels, default = {}, 1
    for i, item in ipairs(items) do
        labels[i] = item.label
        if item.current then default = i end
    end
    input.select({
        prompt = prompt_text,
        items = labels,
        default_item = default,
        submit = function(i) send(items[i].event) end,
    })
end

mp.register_script_message("sources", function(json)
    local d = parse(json)
    local items = d.items or {}
    for _, item in ipairs(items) do item.event = { type = "source", id = item.id } end
    pick("cloudstream-sources", d.loading and "Source (still resolving):" or "Source:", items, d.loading)
    -- Keep an open uosc menu filling in while links still arrive.
    if d.loading and uosc.menu == "cloudstream-sources" then
        mp.add_timeout(1, function()
            if uosc.menu == "cloudstream-sources" then send({ type = "sources" }) end
        end)
    end
end)

mp.register_script_message("episodes", function(json)
    local d = parse(json)
    local items = d.items or {}
    for _, item in ipairs(items) do
        item.event = { type = "episode", index = item.index }
        item.title = item.label
        if item.watched then
            item.label = item.label .. "  ✓"
            item.hint = "watched"
        end
    end
    pick("cloudstream-episodes", "Episode:", items, false)
end)

mp.add_forced_key_binding(opts.key_next, "cs-next", function() send({ type = "next" }) end)
mp.add_forced_key_binding(opts.key_prev, "cs-prev", function() send({ type = "prev" }) end)
mp.add_forced_key_binding(opts.key_sources, "cs-sources", function() send({ type = "sources" }) end)
mp.add_forced_key_binding(opts.key_episodes, "cs-episodes", function() send({ type = "episodes" }) end)
mp.add_forced_key_binding(opts.key_watched_next, "cs-watched-next", function() send({ type = "watched-next" }) end)
