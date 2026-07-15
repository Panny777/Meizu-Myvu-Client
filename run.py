"""Entry point: scan, connect, pair, and stream from the MYVU glasses.

Usage:
  python run.py                 # scan and list MYVU/StarryNet devices
  python run.py <ADDRESS>       # connect + pair + listen
  python run.py <ADDRESS> --mac 7C:A3:75:D0:94:F1   # spoof a specific identifier

On Windows <ADDRESS> is the BLE MAC (as shown by the scan).
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import uuid

from dotenv import load_dotenv

from myvu.client import MyvuClient

LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "myvu.log")
load_dotenv(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".env"))


def configure_logging(log_file: str = LOG_FILE) -> logging.Handler:
    """Two-tier logging: the console shows milestones and errors only; the
    full blow-by-blow (every packet, every ACK, every telemetry message) goes
    to `log_file`. Returns the console handler so callers can raise its level
    (e.g. --debug). """
    file_handler = logging.FileHandler(log_file, mode="a", encoding="utf-8")
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)-5s %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S"))

    console_handler = logging.StreamHandler()
    console_handler.setLevel(logging.INFO)
    console_handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)-5s %(message)s", datefmt="%H:%M:%S"))

    logging.basicConfig(level=logging.WARNING, handlers=[file_handler, console_handler])
    logging.getLogger("myvu").setLevel(logging.DEBUG)  # full detail -> file only
    logging.getLogger("myvu").info("Full detail is being logged to %s", log_file)
    return console_handler


def _onoff(arg: str) -> bool:
    """Parse an on/off argument for boolean toggle commands. Empty or any of
    on/1/true/yes/enable -> True; everything else -> False."""
    return arg.strip().lower() in ("", "on", "1", "true", "yes", "enable", "enabled")


async def do_scan() -> None:
    print("scanning for StarryNet devices (8s)...")
    devices = await MyvuClient.scan()
    if not devices:
        print("  none found. Make sure the glasses are on and advertising.")
        return
    for d in devices:
        print(f"  {d.address}  rssi={getattr(d, 'rssi', '?')}  name={d.name!r}")


HELP = """
commands:

  notify <text>
      Push a notification card to the lens with a generic "Notification"
      title. Confirmed working live: the card actually renders on the
      display. Example: notify Standup starts in 5 minutes

  notify <title> | <body>
      Same as above but with your own title. Example:
      notify Meeting | Standup starts in 5 minutes

  tici <text>
      Open the teleprompter app on the glasses and load this text as the
      scrolling script. Use \\n inside <text> for line breaks. Example:
      tici Welcome to my talk.\\nFirst point here.

  hl <index>
      Scroll/highlight the currently-open teleprompter to paragraph
      <index> (0-based). Has no effect unless 'tici' was run first in this
      session (it tracks the last-opened file key).

  vol <0-15>
      Set the glasses' audio volume. 0 = silent, 15 = max (matches the
      range observed in the glasses' own telemetry).

  bright <value>
      Set the glasses' screen brightness. Observed range is roughly 0-10
      in telemetry; the exact ceiling isn't confirmed.

  wifi on|off
      Turn the glasses' own WiFi radio on or off.

  fov <0-3>
      Set the field-of-view position of the standby widgets shown while
      the glasses are idle/on standby.

  query <action>
      Send a no-argument status query and let the glasses answer in the
      background (the reply shows up in myvu.log, not inline here --
      tail -f myvu.log to watch it live). Known query names:
        get_device_info   get_language          get_zen_mode
        get_air_mode      get_screen_off_time   get_wear_detection_mode
        get_music_tp_control_mode   get_network_valid   get_glass_log
        request_wifi_list           request_phone_battery
        get_standby_widget_lists
      Example: query get_device_info

  raw <json>
      Send any hand-written app-action JSON straight to the launcher.
      Use this to experiment with actions that don't have a dedicated
      command yet. Example:
      raw {"action":"system","data":{"action":"get_device_info"}}

  ask <question>
      Text-only stand-in for the glasses' AI assistant. Generates an
      answer with the Claude API and pushes it to the lens through the
      same JSON 'code' protocol the real assistant uses (an ASR caption
      for your question, then a TTS caption for the answer) -- no real
      microphone capture or speaker audio involved, since that would need
      the parked classic-Bluetooth transport. Whether the lens visibly
      reacts to text-only messages (vs. requiring real audio activity)
      hasn't been confirmed on real hardware -- this is an experiment.
      Requires `pip install anthropic` and an API key: set
      ANTHROPIC_API_KEY, or run `ant auth login`. Example:
      ask What's a good icebreaker for a team meeting?

  setq <question>
      Set the pre-set question sent when the glasses AI button is pressed
      (code:3 control:1). The glasses allow only ~2 seconds (muteTimeout)
      before showing "service error", so the button cannot block for user
      input -- it responds instantly with this stored question instead.
      Call setq with no argument to see the current question. Example:
      setq What is the weather like today?

  synctime
      Push this PC's wall-clock time and UTC offset to the glasses so their
      clock matches (action 'SyncOffSetTime', same as the official app). This
      runs automatically on connect; use it to re-sync manually.

  nav route <from> -> <to> | start | demo | stop | info ... | open
      Drive the glasses' AR navigation HUD (the glasses render it from
      structured data we stream). This is the phone-initiated 'start
      navigation on glasses' path -- start_nav opens the HUD *with* initial
      data (an open_app whose ext carries the first nav frame), then we
      stream navi_info updates.
        nav live <dest> [@COMx] -- LIVE turn-by-turn from a serial NMEA GPS:
                      routes current position -> <dest>, map-matches each GPS
                      fix onto the route, streams updates, and reroutes when
                      you go off-route. GPS port defaults to $MYVU_GPS_PORT or
                      COM3 (override inline with @COM5). Example:
                      nav live Bagamoyo @COM5
        nav route <from> -> <to> -- REAL route but position SIMULATED (no GPS):
                      geocode + OSRM route, drive the HUD along it. <from>/<to>
                      are place names or lat,lon. Example:
                      nav route Dar es Salaam -> Bagamoyo
        nav demo   -- start the HUD then stream a SIMULATED canned route (to
                      prove the HUD renders without any network/routing)
        nav start [road] -- just open the HUD and start navigation
        nav stop   -- end navigation
        nav info <icon> <dist_to_turn_m> [road]  -- send one manual frame
                      (icon = maneuver type; exact meanings are firmware-
                      defined, so experiment to see which arrow each shows)
        nav open   -- just launch the nav app (no HUD data)

  system settings (mirror the official app's ControlUtils):
    lang <language> <country>   set UI/voice language, e.g. lang en US
    name <text>                 rename the glasses
    screenoff <seconds>         display auto-off timeout, e.g. screenoff 30
    zen [on|off]                do-not-disturb (default on)
    air [on|off]                minimal mode -- CLOSES ALL APPS and may
                                restrict functions (default on)
    wear [on|off]               auto on/off when worn (default on)
    musictp [on|off]            music touch-panel control mode (default on)
      For any of these, run 'query get_<name>' to read the current value
      (e.g. query get_zen_mode) -- the reply shows up in the log.

  help            show this help
  quit / q        disconnect and exit
"""


async def repl(client) -> None:
    loop = asyncio.get_event_loop()

    # Preset question used as a fallback if voice input isn't available
    # (glasses not connected as a Windows audio device, or STT fails).
    ai_button_question = ["What time is it?"]

    # --- real voice assistant: AI button starts a listening session ---------
    # The AI button is a SHORT press that *starts* a conversation (not
    # hold-to-talk). On press we send code:4 (session ack) and record the
    # glasses' Windows HFP mic until you stop speaking (silence detection),
    # transcribe with Groq, answer, and SPEAK it over A2DP. Then -- like the
    # real glasses -- we loop straight back to listening for a follow-up. The
    # conversation ends when you stay silent (record times out) or you press
    # the button again. See myvu/voice.py.
    from myvu import voice
    vstate = {"active": False, "stop": False}

    # Say any of these (alone) to end the conversation.
    STOP_PHRASES = {"stop", "goodbye", "good bye", "bye", "exit", "quit",
                    "that's all", "thats all", "that is all", "never mind",
                    "nevermind", "thank you", "thanks", "cancel", "end"}

    def _is_stop_phrase(text: str) -> bool:
        t = text.strip().lower().rstrip(".!?,")
        return t in STOP_PHRASES

    # --- simulated navigation route (Phase 1: prove the glasses render the AR
    # nav overlay from our client, no GPS/routing engine needed) --------------
    navstate = {"task": None}

    # (icon_type, road name, leg length in metres). icon_type is the glasses'
    # maneuver icon; exact meanings are firmware-defined, so the demo cycles a
    # few so you can see which arrow each renders.
    _DEMO_ROUTE = [
        (2, "Main Street", 300),
        (3, "Elm Avenue", 500),
        (1, "Highway 1", 1200),
        (4, "Market Square", 250),
        (2, "Finish", 0),
    ]

    async def _nav_demo():
        total = sum(leg for _ic, _n, leg in _DEMO_ROUTE)
        travelled = 0
        speed_mps = 14  # ~50 km/h
        try:
            # open the HUD *with* initial nav data (this is what actually starts
            # navigation on the glasses), then stream updates
            first_ic, first_road, first_leg = _DEMO_ROUTE[0]
            await client.start_nav(icon_type=first_ic, path_distance=total,
                                   path_retain_distance=total,
                                   next_road_name=first_road,
                                   next_road_distance=first_leg,
                                   navi_speed=str(int(speed_mps * 3.6)))
            await asyncio.sleep(2.0)  # let the HUD open / navi_start_rsp
            for ic, road, leg in _DEMO_ROUTE:
                remaining_to_turn = leg
                while remaining_to_turn > 0:
                    prd = total - travelled            # remaining on route
                    prt = int(prd / speed_mps)         # remaining seconds
                    await client.send_navi_info(
                        icon_type=ic, path_distance=total,
                        path_retain_distance=prd, path_remain_time=prt,
                        next_road_name=road, next_road_distance=remaining_to_turn,
                        navi_speed=str(int(speed_mps * 3.6)), gps_status=1)
                    step = min(100, remaining_to_turn)
                    remaining_to_turn -= step
                    travelled += step
                    await asyncio.sleep(1.0)
            print("[nav] demo route finished")
            await client.nav_stop()
        except asyncio.CancelledError:
            await client.nav_stop()
            raise
        except Exception as e:  # noqa: BLE001
            print(f"[nav] demo error: {e}")
        finally:
            navstate["task"] = None

    async def _nav_route(origin_s: str, dest_s: str, speedup: float = 6.0):
        """Fetch an OSRM route origin->dest and drive the HUD along it. origin/
        dest are 'lat,lon' or place names. `speedup` compresses real drive time
        so a long route is watchable. No GPS -- position is simulated along the
        route at each step's own average speed."""
        from myvu import navigation
        try:
            print("[nav] geocoding + routing (OSRM)...")
            origin = await loop.run_in_executor(None, navigation.parse_point, origin_s)
            dest = await loop.run_in_executor(None, navigation.parse_point, dest_s)
            route = await loop.run_in_executor(None, navigation.route, origin, dest)
            total = route.total_distance
            print(f"[nav] route: {len(route.steps)} steps, {total/1000:.1f} km, "
                  f"~{route.total_duration/60:.0f} min")
            steps = route.steps
            travelled = 0
            first = steps[0] if steps else None
            await client.start_nav(
                icon_type=steps[1].ic if len(steps) > 1 else 15,
                path_distance=total, path_retain_distance=total,
                next_road_name=(steps[1].road if len(steps) > 1 else "Arrive"),
                next_road_distance=first.distance if first else 0, navi_speed="0")
            await asyncio.sleep(2.0)
            for i, step in enumerate(steps):
                nxt = steps[i + 1] if i + 1 < len(steps) else None
                ic = nxt.ic if nxt else 15            # 15 = arrive (provisional)
                road = (nxt.road if nxt and nxt.road else step.road) or "Continue"
                # metres/sec for this leg (fallback ~12 m/s), sped up
                mps = (step.distance / step.duration if step.duration > 0 else 12.0)
                mps = max(mps, 3.0) * speedup
                remaining = step.distance
                while remaining > 0:
                    prd = total - travelled
                    prt = int((route.total_duration) * (prd / total)) if total else 0
                    await client.send_navi_info(
                        icon_type=ic, path_distance=total, path_retain_distance=prd,
                        path_remain_time=prt, next_road_name=road,
                        next_road_distance=int(remaining),
                        navi_speed=str(int(mps / speedup * 3.6)), gps_status=1)
                    move = min(remaining, mps)
                    remaining -= move
                    travelled += move
                    await asyncio.sleep(1.0)
            print("[nav] arrived — route finished")
            await client.nav_stop()
        except asyncio.CancelledError:
            await client.nav_stop()
            raise
        except Exception as e:  # noqa: BLE001
            print(f"[nav] route error: {e}")
        finally:
            navstate["task"] = None

    async def _nav_live(dest_s: str, port: str, baud: int):
        """LIVE turn-by-turn: read a serial NMEA GPS, route current->dest with
        OSRM, then map-match each GPS fix onto the route and stream navi_info.
        Reroutes when the driver goes off-route. Ends on arrival / nav stop."""
        from myvu import navigation, gps
        src = gps.SerialNmeaGps(port, baud)
        try:
            await loop.run_in_executor(None, src.open)
            print(f"[nav] waiting for a GPS fix on {port}...")
            fix = await loop.run_in_executor(None, src.wait_for_fix, 60.0)
            if fix is None:
                print("[nav] no GPS fix (check the dongle / antenna / COM port)")
                return
            dest = await loop.run_in_executor(None, navigation.parse_point, dest_s)
            route = await loop.run_in_executor(
                None, navigation.route, (fix.lat, fix.lon), dest)
            tracker = navigation.RouteTracker(route)
            print(f"[nav] LIVE: {route.total_distance/1000:.1f} km, "
                  f"~{route.total_duration/60:.0f} min — driving")
            first = route.steps[1] if len(route.steps) > 1 else None
            await client.start_nav(
                icon_type=first.ic if first else 15, path_distance=route.total_distance,
                path_retain_distance=route.total_distance,
                next_road_name=first.road if first else "Arrive",
                next_road_distance=int(first.at) if first else 0, navi_speed="0")
            off_count = 0
            while True:
                await asyncio.sleep(1.0)
                fix = src.latest()
                if fix is None or not fix.valid:
                    continue
                st = tracker.update(fix.lat, fix.lon)
                if st.off_route:
                    off_count += 1
                    if off_count >= 3:  # ~3s off-route -> reroute
                        print(f"[nav] off-route ({st.deviation:.0f} m) — rerouting")
                        route = await loop.run_in_executor(
                            None, navigation.route, (fix.lat, fix.lon), dest)
                        tracker = navigation.RouteTracker(route)
                        off_count = 0
                    continue
                off_count = 0
                if st.remaining < 25:
                    print("[nav] arrived")
                    break
                nxt = st.next_step
                prt = (int(st.remaining / fix.speed_mps) if fix.speed_mps > 1
                       else int(route.total_duration * st.remaining
                                / max(1, route.total_distance)))
                await client.send_navi_info(
                    icon_type=nxt.ic if nxt else 15,
                    path_distance=route.total_distance,
                    path_retain_distance=int(st.remaining), path_remain_time=prt,
                    next_road_name=(nxt.road if nxt and nxt.road else "Continue"),
                    next_road_distance=int(st.dist_to_next),
                    navi_speed=str(int(fix.speed_mps * 3.6)), gps_status=1)
            await client.nav_stop()
        except asyncio.CancelledError:
            await client.nav_stop()
            raise
        except Exception as e:  # noqa: BLE001
            print(f"[nav] live error: {e}")
        finally:
            await loop.run_in_executor(None, src.close)
            navstate["task"] = None

    async def _ai_button_down():
        if vstate["active"]:
            # Already in a turn. This is almost always the SAME button press
            # echoed on both the BLE and classic-BT relay channels (we listen on
            # both), so just ignore it -- do NOT treat it as a stop. Leaving the
            # AI page comes through as control:0 -> _ai_page_closed instead.
            return
        vstate["active"] = True
        vstate["stop"] = False
        try:
            turn = 0
            while not vstate["stop"]:
                sid = str(uuid.uuid4())
                await client.ai_session_ack(sid)  # code:4 -- (re)enter listening
                print("[AI] listening — speak now (say 'stop' to end)..."
                      if turn == 0 else "[AI] listening for a follow-up "
                                        "(say 'stop' to end)...")
                pcm, sr = await loop.run_in_executor(
                    None, lambda: voice.record_until_silence(
                        should_stop=lambda: vstate["stop"]))
                if vstate["stop"]:
                    break
                if len(pcm) == 0:
                    if turn == 0:
                        print("[AI] mic unavailable or no speech — glasses "
                              "connected as a Windows audio device?")
                    else:
                        print("[AI] no follow-up — conversation ended.")
                    break
                print("[AI] transcribing...")
                text = await loop.run_in_executor(None, voice.transcribe, pcm, sr)
                if not text:
                    print("[AI] (no speech recognized) — conversation ended.")
                    break
                print(f"[AI] you said: {text!r}")
                if _is_stop_phrase(text):
                    print("[AI] stop phrase heard — conversation ended.")
                    break
                # Run the answer pipeline in PARALLEL with streaming the caption:
                # fire the Claude request the instant we have the transcription,
                # stream the recognized-text caption concurrently, then start
                # Groq TTS synthesis as soon as the answer lands (overlapping any
                # remaining caption). Playback still comes after the caption so
                # the question is shown before the answer is spoken.
                answer_task = asyncio.create_task(client._generate_ai_answer(text))
                caption_task = asyncio.create_task(
                    client.ai_send_recognized(sid, text))
                ans = await answer_task
                print(f"AI: {ans}")
                # run_in_executor returns a Future that's already running -- await
                # it directly (don't wrap in create_task, which wants a coroutine)
                synth_fut = loop.run_in_executor(None, voice.synthesize, ans)
                await caption_task          # caption fully shown FIRST
                # Only NOW tell the glasses we've moved from listening ->
                # processing (VrState.VR_PROCESSION). This must come AFTER the
                # ASR caption -- sending it first makes the glasses drop the
                # code:101 caption frames. It stops their ~8s listening timeout
                # from auto-closing the AI page during the (longer) TTS playback.
                await client.ai_sync_vr_state(client.VR_PROCESSION)
                prepared = await synth_fut  # TTS audio ready (synthesized in parallel)

                async def _play_prepared(_answer):
                    return await loop.run_in_executor(None, voice.play, prepared)

                await client.ai_send_answer(ans, speak=_play_prepared)  # SPEAK A2DP
                turn += 1
        except Exception as e:  # noqa: BLE001
            print(f"[AI] error: {e}")
        finally:
            # tell the glasses to close the AI page / stop listening too
            try:
                await client.ai_stop_listening()
            except Exception:  # noqa: BLE001
                pass
            vstate["active"] = False
            vstate["stop"] = False

    async def _ai_page_closed():
        # The glasses sent control:0 (WakeupControl.CLOSE) -- the user pressed
        # the AI button again to leave the AI page. Stop our loop + abort any
        # in-progress recording. NB: this is safe now only because we send
        # VR_PROCESSION during processing, which stops the glasses' listening
        # timeout from emitting a spurious close mid-answer.
        if vstate["active"] and not vstate["stop"]:
            vstate["stop"] = True
            print("[AI] AI button pressed again — stopping.")

    # Register on this client AND its sibling (events may arrive over the
    # classic-BT relay or the BLE channel; run_glasses.py sets rf._sibling=ble).
    for _c in (client, getattr(client, "_sibling", None)):
        if _c is not None:
            _c._ai_button_callback = _ai_button_down
            _c._ai_button_release_callback = _ai_page_closed
    print(HELP)
    while True:
        if not client.is_connected:
            # The link dropped. A reconnect supervisor (run_glasses relay) may
            # bring it back, so wait out a grace period before giving up rather
            # than exiting on a transient drop.
            for _ in range(12):
                await asyncio.sleep(1.0)
                if client.is_connected:
                    break
            if not client.is_connected:
                print("!! link is down, exiting REPL")
                return
            print("link back up.")
        try:
            line = (await loop.run_in_executor(None, input, "myvu> ")).strip()
        except (EOFError, KeyboardInterrupt):
            return
        if not line:
            continue
        cmd, _, arg = line.partition(" ")
        cmd = cmd.lower()
        arg = arg.strip()
        try:
            if cmd in ("quit", "q", "exit"):
                return
            elif cmd == "help":
                print(HELP)
            elif cmd == "notify":
                if "|" in arg:
                    title, _, body = arg.partition("|")
                    await client.push_notification(title.strip(), body.strip())
                else:
                    await client.push_notification("Notification", arg)
            elif cmd == "tici":
                await client.open_teleprompter(arg or "Hello from Python!")
            elif cmd == "hl":
                await client.teleprompter_highlight(int(arg))
            elif cmd == "vol":
                await client.set_volume(int(arg))
            elif cmd == "bright":
                await client.set_brightness(int(arg))
            elif cmd == "wifi":
                if arg.lower() not in ("on", "off"):
                    print("usage: wifi on|off")
                else:
                    await client.toggle_wifi(arg.lower() == "on")
            elif cmd == "fov":
                await client.set_standby_position(int(arg))
            elif cmd == "query":
                if not arg:
                    print("usage: query <action-name>, e.g. query get_device_info")
                else:
                    await client.query(arg)
            elif cmd == "synctime":
                await client.sync_time()
            elif cmd == "nav":
                sub, _, rest = arg.partition(" ")
                sub = sub.lower()
                if sub == "open":
                    await client.open_nav()
                    print("[nav] opened navigation app on the glasses")
                elif sub == "start":
                    await client.start_nav(
                        icon_type=1, path_distance=1000, path_retain_distance=1000,
                        next_road_name=rest or "Ahead", next_road_distance=300,
                        navi_speed="0")
                    print("[nav] started navigation HUD on the glasses (then "
                          "'nav info ...' to update, or use 'nav demo')")
                elif sub == "demo":
                    if navstate["task"] is not None:
                        print("[nav] a route is already running (nav stop to end)")
                    else:
                        navstate["task"] = asyncio.create_task(_nav_demo())
                        print("[nav] streaming a simulated route — watch the lens "
                              "(nav stop to end)")
                elif sub == "route":
                    if navstate["task"] is not None:
                        print("[nav] a route is already running (nav stop to end)")
                    elif "->" not in rest:
                        print("usage: nav route <from> -> <to>   (place names or "
                              "lat,lon), e.g. nav route Dar es Salaam -> Bagamoyo")
                    else:
                        frm, _, to = rest.partition("->")
                        navstate["task"] = asyncio.create_task(
                            _nav_route(frm.strip(), to.strip()))
                        print("[nav] routing (OSRM) and driving the HUD — watch the "
                              "lens (nav stop to end)")
                elif sub == "live":
                    if navstate["task"] is not None:
                        print("[nav] a route is already running (nav stop to end)")
                    elif not rest.strip():
                        print("usage: nav live <destination> [@COMx]   "
                              "(GPS port defaults to $MYVU_GPS_PORT or COM3)")
                    else:
                        dest_s, _, port_ovr = rest.partition("@")
                        port = (port_ovr.strip() or
                                os.environ.get("MYVU_GPS_PORT", "COM3"))
                        baud = int(os.environ.get("MYVU_GPS_BAUD", "9600"))
                        navstate["task"] = asyncio.create_task(
                            _nav_live(dest_s.strip(), port, baud))
                        print(f"[nav] LIVE navigation via GPS on {port} — watch the "
                              "lens (nav stop to end)")
                elif sub == "stop":
                    t = navstate["task"]
                    if t is not None:
                        t.cancel()
                    else:
                        await client.nav_stop()
                    print("[nav] stopped")
                elif sub == "info":
                    # nav info <icon> <dist_to_turn> <road name>
                    parts = rest.split(maxsplit=2)
                    if len(parts) < 2:
                        print("usage: nav info <icon> <dist_to_turn_m> [road name]")
                    else:
                        await client.send_navi_info(
                            icon_type=int(parts[0]), next_road_distance=int(parts[1]),
                            next_road_name=parts[2] if len(parts) > 2 else "",
                            path_retain_distance=int(parts[1]))
                        print("[nav] sent one navi_info frame")
                else:
                    print("usage: nav live <dest> [@COMx] | route <from> -> <to> "
                          "| start | demo | stop | info <icon> <dist> [road] | open")
            elif cmd == "lang":
                parts = arg.split()
                if len(parts) != 2:
                    print("usage: lang <language> <country>, e.g. lang en US")
                else:
                    await client.set_language(parts[0], parts[1])
            elif cmd == "name":
                if not arg:
                    print("usage: name <new device name>")
                else:
                    await client.set_device_name(arg)
            elif cmd == "screenoff":
                if not arg.isdigit():
                    print("usage: screenoff <seconds>, e.g. screenoff 30")
                else:
                    await client.set_screen_off_time(int(arg))
            elif cmd == "zen":
                await client.set_zen_mode(_onoff(arg))
            elif cmd == "air":
                await client.set_air_mode(_onoff(arg))
            elif cmd == "wear":
                await client.set_wear_detection(_onoff(arg))
            elif cmd == "musictp":
                await client.set_music_tp_control(_onoff(arg))
            elif cmd == "raw":
                await client.send_action(arg)
            elif cmd == "ask":
                if not arg:
                    print("usage: ask <question>")
                else:
                    print("thinking...")
                    answer = await client.ask_ai(arg)
                    print(f"AI: {answer}")
            elif cmd == "setq":
                if not arg:
                    print(f"current AI button question: {ai_button_question[0]!r}")
                else:
                    ai_button_question[0] = arg
                    print(f"AI button question set to: {arg!r}")
            elif cmd == "capturemic":
                secs = float(arg) if arg else 6.0
                print(f"capturing mic for {secs:.0f}s — PRESS THE AI BUTTON and speak...")
                stats = await client.capture_mic(secs)
                print(f"captured {stats['frames']} frames, {stats['bytes']} bytes "
                      f"-> {stats['path']} (see log for codec head bytes)")
            else:
                print(f"unknown command: {cmd!r} (try 'help')")
        except Exception as e:  # noqa: BLE001
            print(f"error: {e}")


async def do_run(address: str, own_mac: str, bt_status: int,
                 connect_timeout: float) -> None:
    client = MyvuClient(address, own_mac=own_mac, bt_status=bt_status,
                        connect_timeout=connect_timeout)
    try:
        await client.connect()
        await client.pair()
        await client.establish_session()
        client.start_drains()          # print glasses responses live
        await client.send_init_burst()
        await asyncio.sleep(1.5)
        await client.sync_time()  # match the glasses' clock to this PC on connect
        await client.set_wear_detection(True)  # default wear detection on (app default)
        await client.set_zen_mode(False)       # default do-not-disturb off
        await client.set_screen_off_time(10)   # default display auto-off to 10s
        await repl(client)
    finally:
        await client.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("address", nargs="?", help="BLE address of the glasses")
    ap.add_argument("--mac", default="aa:bb:cc:dd:ee:ff",
                    help="identifier/MAC to present to the glasses")
    ap.add_argument("--bt-status", type=int, default=0,
                    help="BTSTATUS value (0-11) to report in DeviceInfo.btStatus "
                         "-- see myvu/linkproto.py BTSTATUS_* constants. Default "
                         "0=DEFAULT. Try 3=NOBOND to test whether it's what makes "
                         "the glasses open classic-BT pairing for --mac.")
    ap.add_argument("--connect-timeout", type=float, default=20.0,
                    help="seconds to wait for the initial BLE connect (default "
                         "20; bleak's own default is 10, which can be tight on "
                         "some Linux/BlueZ D-Bus setups)")
    ap.add_argument("--debug", action="store_true",
                    help="also show full packet-level detail on the console "
                         "(normally only in myvu.log)")
    ap.add_argument("--log-file", default=LOG_FILE,
                    help="where to write the full-detail log (default: myvu.log)")
    args = ap.parse_args()

    console_handler = configure_logging(args.log_file)
    if args.debug:
        console_handler.setLevel(logging.DEBUG)

    if not args.address:
        asyncio.run(do_scan())
    else:
        asyncio.run(do_run(args.address, args.mac, args.bt_status,
                           args.connect_timeout))


if __name__ == "__main__":
    main()
