"""Converts RNNoise's built-in model (src/rnn_data.c of xiph/rnnoise v0.1.1, BSD-3) into:

  - app/src/main/assets/rnnoise.bin : what PixelGo loads (see editor/denoise/RnNoise.kt)
      int32 "RNN1", then 6 layers in order (input_dense, vad_gru, noise_gru, denoise_gru,
      denoise_output, vad_output), each: int32 inputs, int32 neurons, int32 activation
      (0 tanh, 1 sigmoid, 2 relu), int32 kind (0 dense, 1 GRU), then int8 arrays:
      input weights, [recurrent weights for GRUs], bias. Big-endian ints.
  - an .rnnn text model ("rnnoise-nu model file version 1") with the same weights, which
    ffmpeg's arnndn filter reads; used to make the reference output for the JVM test.

usage: python tools/rnnoise_weights.py path/to/rnn_data.c out.bin out.rnnn
"""
import re
import struct
import sys

LAYERS = [  # name, kind (0 dense, 1 gru), inputs, neurons, activation
    ('input_dense', 0, 42, 24, 0),
    ('vad_gru', 1, 24, 24, 2),
    ('noise_gru', 1, 90, 48, 2),
    ('denoise_gru', 1, 114, 96, 2),
    ('denoise_output', 0, 96, 22, 1),
    ('vad_output', 0, 24, 1, 1),
]


def arrays(src):
    found = {}
    for m in re.finditer(r'static const rnn_weight (\w+)\[(\d+)\] = \{(.*?)\};', src, re.S):
        values = [int(v) for v in re.findall(r'-?\d+', m.group(3))]
        assert len(values) == int(m.group(2)), m.group(1)
        found[m.group(1)] = values
    return found


def main(c_file, bin_out, rnnn_out):
    a = arrays(open(c_file, encoding='utf-8').read())
    binary = bytearray(b'RNN1')
    text = ['rnnoise-nu model file version 1']
    for name, kind, n_in, n_out, act in LAYERS:
        gates = 3 if kind == 1 else 1
        weights = a[name + '_weights']
        bias = a[name + '_bias']
        assert len(weights) == n_in * n_out * gates and len(bias) == n_out * gates, name
        parts = [weights]
        if kind == 1:
            recurrent = a[name + '_recurrent_weights']
            assert len(recurrent) == n_out * n_out * 3, name
            parts.append(recurrent)
        parts.append(bias)
        binary += struct.pack('>iiii', n_in, n_out, act, kind)
        for p in parts:
            binary += struct.pack('%db' % len(p), *p)
        text.append('%d %d %d' % (n_in, n_out, act))
        for p in parts:
            text.append(' '.join(str(v) for v in p))
    open(bin_out, 'wb').write(binary)
    open(rnnn_out, 'w', newline='\n').write('\n'.join(text) + '\n')
    print('wrote', bin_out, len(binary), 'bytes and', rnnn_out)


if __name__ == '__main__':
    main(*sys.argv[1:4])
