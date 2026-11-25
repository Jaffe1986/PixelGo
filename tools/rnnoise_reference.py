"""Makes the test signal for RnNoiseTest: 2 s of speech-like sound (a harmonic "voice" with a
sliding pitch and syllable rhythm, 0.4..1.4 s) over noise and hum, 48 kHz mono 16-bit WAV.
The reference output is ffmpeg's arnndn with the same weights (see rnnoise_weights.py).
ffmpeg's port leaves out RNNoise's 1/960 FFT scaling and feeds samples on a +-1 scale instead of
RNNoise's 16-bit scale, so it runs the network on spectra 960/32768 times too small. Scaling the
input by 32768/960 (and the output back) makes it compute exactly what the original library does:

  python tools/rnnoise_reference.py noisy.wav
  ffmpeg -i noisy.wav -af "volume=34.133333333:precision=float,arnndn=m=rnnoise_v1.rnnn,volume=0.029296875:precision=float" -c:a pcm_f32le clean.wav
"""
import math
import random
import struct
import sys

RATE = 48000


def signal():
    rnd = random.Random(1234)
    n = 2 * RATE
    out = []
    lp = 0.0
    phase = [0.0] * 30
    for i in range(n):
        t = i / RATE
        # Noise: low-passed white noise plus a little 100 Hz hum.
        lp = 0.85 * lp + 0.15 * rnd.gauss(0, 1)
        noise = 0.05 * lp + 0.01 * math.sin(2 * math.pi * 100 * t)
        voice = 0.0
        if 0.4 <= t < 1.4:
            f0 = 140 + 40 * (t - 0.4)                      # pitch slides 140 -> 180 Hz
            syllables = 0.5 - 0.5 * math.cos(2 * math.pi * 4 * (t - 0.4))
            for k in range(1, 30):
                phase[k] += 2 * math.pi * f0 * k / RATE
                formants = 1.0 / (1 + ((f0 * k - 700) / 300) ** 2) + 0.5 / (1 + ((f0 * k - 1800) / 400) ** 2)
                voice += math.sin(phase[k]) * formants / k
            voice *= 0.25 * syllables
        out.append(voice + noise)
    return out


def write_wav(path, samples):
    data = b''.join(struct.pack('<h', max(-32768, min(32767, int(round(s * 32767))))) for s in samples)
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', 36 + len(data)) + b'WAVE')
        f.write(b'fmt ' + struct.pack('<IHHIIHH', 16, 1, 1, RATE, RATE * 2, 2, 16))
        f.write(b'data' + struct.pack('<I', len(data)) + data)


if __name__ == '__main__':
    write_wav(sys.argv[1], signal())
    print('wrote', sys.argv[1])
