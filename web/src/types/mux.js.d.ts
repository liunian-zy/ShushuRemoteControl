declare module 'mux.js' {
  namespace mp4 {
    class Transmuxer {
      constructor(options?: {
        keepOriginalTimestamps?: boolean
        remux?: boolean
      })

      on(event: 'data', callback: (segment: {
        initSegment: Uint8Array
        data: Uint8Array
        type: string
      }) => void): void

      on(event: 'done', callback: () => void): void

      push(data: Uint8Array): void
      flush(): void
      reset(): void
    }
  }

  export = { mp4 }
}
