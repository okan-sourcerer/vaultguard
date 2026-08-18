// Firefox exposes the promise-based `browser.*` API; Chrome exposes callback-based
// `chrome.*` and, in MV3, a promise-based `chrome.*` for most methods.
//
// The one that actually differs for this extension is runtime.connectNative / sendNativeMessage,
// so rather than pull in the full Mozilla polyfill, this defines the small surface used here.
globalThis.browser = globalThis.browser || globalThis.chrome;
