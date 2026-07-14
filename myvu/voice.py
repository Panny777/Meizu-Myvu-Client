"""Voice input for the MYVU AI assistant: record from the glasses' Windows HFP
mic and transcribe with Groq (Whisper-large-v3-turbo).

Why the Windows mic (not the relay audio): when the glasses are paired as a
Windows AUDIO device, their microphone shows up as a normal Windows input
device ("...Hands-Free AG Audio (MYVU DC47)") and Windows decodes the HFP
audio to plain PCM for us -- no codec work. (The alternative, code:109 relay
frames, is a compressed codec we'd have to reverse-engineer, and the glasses
only stream it when they're NOT the Windows mic -- see capture_mic in
applayer.py.) So keep the glasses connected as the Windows audio device.

Deps: sounddevice, scipy, openai (used against Groq's OpenAI-compatible
endpoint), python-dotenv. Needs GROQ_API_KEY in the environment/.env.
Windows only in practice (that's where the HFP mic device appears).
"""
from __future__ import annotations

import io
import logging
import os
import wave

log = logging.getLogger("myvu.voice")

# All three are overridable from the environment / .env (defaults shown).
# GROQ_TTS_VOICE options for the Orpheus model: autumn diana hannah austin daniel troy
GROQ_STT_MODEL = os.environ.get("GROQ_STT_MODEL", "whisper-large-v3-turbo")
GROQ_TTS_MODEL = os.environ.get("GROQ_TTS_MODEL", "canopylabs/orpheus-v1-english")
GROQ_TTS_VOICE = os.environ.get("GROQ_TTS_VOICE", "hannah")
_MIC_NAME_HINT = "MYVU DC47"


def find_myvu_mic() -> tuple[int, int] | None:
    """Return (device_index, sample_rate) of the MYVU HFP mic, or None."""
    import sounddevice as sd
    for i, d in enumerate(sd.query_devices()):
        if d["max_input_channels"] > 0 and _MIC_NAME_HINT in d["name"]:
            sr = int(d["default_samplerate"]) or 8000
            return i, sr
    return None


def find_myvu_speaker() -> tuple[int, int, int] | None:
    """Return (device_index, sample_rate, channels) of the MYVU A2DP stereo
    output, or None. Preferred over the Hands-Free output for answer playback
    (A2DP is what the real assistant speaks over)."""
    import sounddevice as sd
    for i, d in enumerate(sd.query_devices()):
        if d["max_output_channels"] > 0 and "MYVU DC47 Stereo" in d["name"]:
            return i, int(d["default_samplerate"]) or 44100, d["max_output_channels"]
    return None


def _groq_tts(text: str):
    """Synthesize `text` with Groq (Orpheus) into (int16 mono PCM, sample_rate).
    Returns None on any failure so the caller can fall back to SAPI."""
    import io
    import wave
    import numpy as np
    from openai import OpenAI

    key = os.environ.get("GROQ_API_KEY")
    if not key:
        return None
    try:
        client = OpenAI(api_key=key, base_url="https://api.groq.com/openai/v1")
        resp = client.audio.speech.create(
            model=GROQ_TTS_MODEL, voice=GROQ_TTS_VOICE, input=text,
            response_format="wav")
        with wave.open(io.BytesIO(resp.read()), "rb") as w:
            sr, ch = w.getframerate(), w.getnchannels()
            audio = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
        if ch > 1:
            audio = audio.reshape(-1, ch).mean(axis=1).astype(np.int16)
        log.info("Groq TTS (%s/%s): %d samples @ %dHz",
                 GROQ_TTS_MODEL, GROQ_TTS_VOICE, len(audio), sr)
        return audio, sr
    except Exception as e:  # noqa: BLE001
        log.warning("Groq TTS failed (%s) -- falling back to SAPI", e)
        return None


def _sapi_tts(text: str):
    """Synthesize `text` with Windows SAPI into (int16 mono PCM, sample_rate),
    or None if SAPI isn't available. Offline fallback for _groq_tts."""
    import tempfile
    import wave
    import numpy as np

    try:
        import win32com.client
    except Exception:  # noqa: BLE001
        log.warning("pywin32 not available for SAPI TTS")
        return None
    tmp = tempfile.mktemp(suffix=".wav")
    try:
        stream = win32com.client.Dispatch("SAPI.SpFileStream")
        stream.Open(tmp, 3)  # SSFMCreateForWrite
        v = win32com.client.Dispatch("SAPI.SpVoice")
        v.AudioOutputStream = stream
        v.Speak(text)
        stream.Close()
        with wave.open(tmp, "rb") as w:
            sr, ch = w.getframerate(), w.getnchannels()
            audio = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    finally:
        try:
            os.remove(tmp)
        except OSError:
            pass
    if ch > 1:
        audio = audio.reshape(-1, ch).mean(axis=1).astype(np.int16)
    return audio, sr


def speak(text: str) -> bool:
    """Synthesize `text` and play it out the MYVU glasses' A2DP speaker, so the
    assistant answer is SPOKEN like the real thing. Uses Groq's natural voice
    (Orpheus) when GROQ_API_KEY is set, otherwise Windows SAPI. Blocking -- call
    via run_in_executor. Returns False if the speaker or TTS isn't available."""
    import numpy as np
    import sounddevice as sd

    if not text:
        return False
    spk = find_myvu_speaker()
    if spk is None:
        log.warning("MYVU speaker not found -- glasses connected as a Windows "
                    "audio device?")
        return False
    dev, dev_sr, dev_ch = spk

    result = _groq_tts(text) or _sapi_tts(text)
    if result is None:
        return False
    audio, sr = result

    if sr != dev_sr:
        from math import gcd
        from scipy.signal import resample_poly
        # NB: resample_poly must be fed float, not int16 -- passing int16
        # silently returns all zeros (silent audio). Reduce up/down by gcd too.
        g = gcd(int(dev_sr), int(sr))
        audio = resample_poly(audio.astype(np.float64),
                              dev_sr // g, sr // g).astype(np.int16)
    if dev_ch >= 2:  # duplicate mono -> stereo for the A2DP device
        audio = np.column_stack([audio, audio])
    log.info("speaking answer over MYVU A2DP (device %d @ %dHz)", dev, dev_sr)
    sd.play(audio, samplerate=dev_sr, device=dev)
    sd.wait()
    return True


def record_until_silence(max_seconds: float = 12.0, silence_dur: float = 1.5,
                         rms_threshold: float = 200.0, start_timeout: float = 5.0,
                         should_stop=None):
    """Record from the MYVU Windows HFP mic until the user stops speaking.

    The real glasses AI button is a short press that *starts* a listening
    session -- the glasses then stream mic audio until end-of-speech, not while
    a button is held. So this opens the mic (which brings up the HFP SCO link),
    waits for speech to start, then stops once there's `silence_dur` seconds of
    continuous silence after speech (or `max_seconds` total, or no speech
    within `start_timeout`). Blocking -- call via run_in_executor.

    `should_stop`, if given, is a no-arg callable polled every ~100ms; when it
    returns True the recording aborts promptly and returns empty (used to let a
    second AI-button press cancel an in-progress listen). Returns (int16 numpy
    PCM, sample_rate). Empty array if the mic isn't found or no speech was heard.
    """
    import time
    import numpy as np
    import sounddevice as sd

    found = find_myvu_mic()
    if found is None:
        log.warning("MYVU mic not found -- are the glasses connected as a "
                    "Windows AUDIO device?")
        return np.zeros(0, dtype="int16"), 8000
    dev, sr = found
    chunk = max(1, int(sr * 0.1))  # 100ms blocks
    frames: list = []
    speech_started = False
    silence_start = None
    t0 = time.time()
    log.info("recording from MYVU mic (device %d @ %dHz) until silence", dev, sr)
    with sd.InputStream(device=dev, samplerate=sr, channels=1, dtype="int16") as stream:
        while True:
            now = time.time()
            if should_stop is not None and should_stop():
                return np.zeros(0, dtype="int16"), sr  # cancelled by caller
            if now - t0 > max_seconds:
                break
            if not speech_started and now - t0 > start_timeout:
                break  # nobody said anything
            data, _ = stream.read(chunk)
            data = np.asarray(data).flatten()
            frames.append(data)
            rms = float(np.sqrt(np.mean(data.astype(np.float64) ** 2))) if len(data) else 0.0
            if rms > rms_threshold:
                speech_started = True
                silence_start = None
            elif speech_started:
                if silence_start is None:
                    silence_start = now
                elif now - silence_start > silence_dur:
                    break  # end of utterance
    if not frames:
        return np.zeros(0, dtype="int16"), sr
    pcm = np.concatenate(frames).flatten()
    log.info("captured %.1fs of audio (%d samples)", len(pcm) / sr, len(pcm))
    return pcm, sr


def transcribe(pcm_int16, sample_rate: int) -> str:
    """Transcribe int16 PCM with Groq Whisper. Returns the recognized text
    (empty string if there was no usable audio)."""
    import numpy as np
    from openai import OpenAI

    if pcm_int16 is None or len(pcm_int16) == 0:
        return ""
    # quick silence guard
    rms = float(np.sqrt(np.mean(pcm_int16.astype(np.float64) ** 2)))
    if rms < 30:
        log.info("audio looks like silence (rms=%.1f) -- skipping STT", rms)
        return ""

    key = os.environ.get("GROQ_API_KEY")
    if not key:
        raise RuntimeError("GROQ_API_KEY not set -- add it to myvu_client/.env")

    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm_int16.tobytes())
    buf.seek(0)
    buf.name = "mic.wav"

    client = OpenAI(api_key=key, base_url="https://api.groq.com/openai/v1")
    result = client.audio.transcriptions.create(
        model=GROQ_STT_MODEL, file=buf, language="en")
    text = (result.text or "").strip()
    log.info("STT (rms=%.1f): %r", rms, text)
    return text
