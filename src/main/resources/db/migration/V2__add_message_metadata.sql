-- Add metadata column to conversation_messages for storing tool calls, reasoning traces, and metrics
ALTER TABLE conversation_messages
ADD COLUMN metadata TEXT;

-- Comment explaining the column purpose
-- metadata: JSON blob containing:
--   - toolCalls: Array of tool invocations with name, arguments, result summary, success status
--   - reasoning: Full reasoning/thinking trace from the model
--   - iterationData: Per-iteration breakdown of the agentic loop
--   - metrics: Iteration count and token usage for this message
