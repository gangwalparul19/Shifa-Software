/** A table returned with an answer (rankings, channel comparisons). Cells are display-ready. */
export interface AssistantTable {
  headers: string[];
  rows: string[][];
}

/** The server's reply to one question. */
export interface AssistantAnswer {
  understood: boolean;
  answer: string;
  table: AssistantTable | null;
  suggestions: string[];
}

/** One bubble in the conversation. */
export interface ChatMessage {
  id: number;
  from: 'user' | 'bot';
  text: string;
  table?: AssistantTable | null;
  suggestions?: string[];
  /** True when the question could not be answered (shown with a warning style). */
  problem?: boolean;
}
