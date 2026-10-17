/** Shared semantic envelope; deliberately independent of physical/network transport. */
export interface SwarMessage {
  type: 'SWAR_MESSAGE'; sessionId: string; sequence: number;
  language: 'hi' | 'en' | 'hinglish'; encoding: 'text_v1'; payload: string; timestamp: number;
}
export type ItantraMessage = SwarMessage;

export function parseLogicalMessage(value: unknown): SwarMessage {
  const v = value as (Partial<SwarMessage> & { type?: string }) | null;
  const isTypeValid = v && (v.type === 'SWAR_MESSAGE' || v.type === 'ITANTRA_MESSAGE');
  if (!v || !isTypeValid || typeof v.sessionId !== 'string'
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v.sessionId)
    || !Number.isSafeInteger(v.sequence) || Number(v.sequence) < 1 || !['hi','en','hinglish'].includes(v.language ?? '')
    || v.encoding !== 'text_v1' || typeof v.payload !== 'string' || Buffer.byteLength(v.payload) < 1
    || Buffer.byteLength(v.payload) > 8192 || !Number.isSafeInteger(v.timestamp) || Number(v.timestamp) < 0) {
    throw new Error('Invalid SWAR_MESSAGE');
  }
  return { type: 'SWAR_MESSAGE', sessionId: v.sessionId, sequence: v.sequence!, language: v.language!, encoding: v.encoding, payload: v.payload, timestamp: v.timestamp! };
}
