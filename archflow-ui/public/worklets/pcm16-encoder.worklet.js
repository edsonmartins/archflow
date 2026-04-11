/*
 * PCM16 encoder audio worklet for the ArchFlow realtime client.
 *
 * Replaces the deprecated ScriptProcessorNode with an AudioWorkletProcessor
 * that runs on the audio rendering thread — no GC pauses, no main-thread
 * jank. Receives Float32 mono frames from the input graph, converts them
 * to little-endian PCM16 and forwards a flattened `Int16Array` to the main
 * thread via `port.postMessage`. The main thread base64-encodes the bytes
 * and sends them over the realtime WebSocket.
 *
 * The worklet is sample-rate agnostic — the containing AudioContext is
 * created with `sampleRate: 24_000` which the browser honors when the
 * hardware supports it. If the browser resamples internally the frames
 * will still be emitted at the context rate, which the server can handle
 * via the `sampleRate` field in the outbound payload.
 *
 * Register with:
 *   await ctx.audioWorklet.addModule('/worklets/pcm16-encoder.worklet.js');
 *   const node = new AudioWorkletNode(ctx, 'pcm16-encoder');
 *   node.port.onmessage = (e) => { ... e.data: Int16Array ... };
 */
class Pcm16EncoderProcessor extends AudioWorkletProcessor {
    process(inputs) {
        const input = inputs[0];
        if (!input || input.length === 0) return true;
        const channel = input[0];
        if (!channel || channel.length === 0) return true;

        const pcm16 = new Int16Array(channel.length);
        for (let i = 0; i < channel.length; i++) {
            const s = Math.max(-1, Math.min(1, channel[i]));
            pcm16[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
        }
        // Transfer the underlying buffer to avoid copying on the boundary.
        this.port.postMessage(pcm16, [pcm16.buffer]);
        return true;
    }
}

registerProcessor('pcm16-encoder', Pcm16EncoderProcessor);
