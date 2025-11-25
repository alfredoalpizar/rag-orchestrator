# Running Agentic Loop Strategy Tests

This guide explains how to run integration tests for agentic loop strategies locally.

## Prerequisites

### 1. Set API Keys

```bash
# DeepSeek API
export DEEPSEEK_API_KEY="sk-..."

# Qwen API (Alibaba DashScope)
export QWEN_API_KEY="sk-..."
```

**Note**: These tests make REAL API calls and will consume tokens/credits.

### 2. Optional: Configure Test Models

Use cheaper/faster models for testing to reduce costs:

```bash
# Use specific models for testing
export DEEPSEEK_TEST_MODEL="deepseek-chat"  # cheaper than deepseek-reasoner
export QWEN_TEST_MODEL_INSTRUCT="qwen-plus"
export QWEN_TEST_MODEL_THINKING="qwen-plus"
```

## Running Tests

### Run All Integration Tests

```bash
./gradlew test --tests "*IntegrationTest" -Dspring.profiles.active=integration-test
```

### Run Specific Strategy Tests

```bash
# Test DeepSeek strategy only
./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest" \
  -Dspring.profiles.active=integration-test

# Test a specific scenario
./gradlew test \
  --tests "DeepSeekSingleStrategyIntegrationTest.should complete simple question without tool calls" \
  -Dspring.profiles.active=integration-test
```

### Run Tests with Different Strategies

```bash
# Test with Qwen thinking model
TEST_STRATEGY=qwen_single_thinking ./gradlew test \
  --tests "*IntegrationTest" \
  -Dspring.profiles.active=integration-test

# Test with Qwen instruct model
TEST_STRATEGY=qwen_single_instruct ./gradlew test \
  --tests "*IntegrationTest" \
  -Dspring.profiles.active=integration-test
```

## Understanding Test Output

### Console Output

Tests print event summaries to the console:

```
==========================================================
TEST: Simple question without tool calls
==========================================================
[0] FinalResponse
    Content: 2 + 2 equals 4.
    Tokens: 15
[1] IterationComplete
    Tokens: 15
    Should Continue: false
==========================================================
📝 Test output saved: /home/user/rag-orchestrator/test-outputs/deepseek_simple_math_2024-11-24_10-30-15.txt
```

### Saved Test Outputs

All test outputs are saved to `test-outputs/` for manual review:

```bash
# List all test outputs
ls -la test-outputs/

# View a specific test output
cat test-outputs/deepseek_simple_math_2024-11-24_10-30-15.txt

# Search for interesting patterns
grep -r "Tool Call" test-outputs/
grep -r "Tokens Used" test-outputs/
```

### Test Output Format

Each saved output includes:
- Test metadata (timestamp, conversation ID, iteration)
- Full event sequence with details
- Content of responses
- Token usage
- Tool calls (if any)

Example:
```
======================================================================
TEST OUTPUT: deepseek_simple_math
======================================================================
Timestamp: 2024-11-24_10-30-15
Conversation ID: test-simple-math
Iteration: 1/5
Streaming Mode: FINAL_ONLY

======================================================================
EVENTS (2 total)
======================================================================

[0] FinalResponse
----------------------------------------------------------------------
Content: 2 + 2 equals 4.
Tokens Used: 15

[1] IterationComplete
----------------------------------------------------------------------
Tokens Used: 15
Should Continue: false
```

## Test Philosophy

### ✅ What Tests Assert

- **Structure**: Event types, sequences, completeness
- **Behavior**: Tool calling, streaming, finalization
- **Bounds**: Token counts, response lengths, timeouts
- **Mechanics**: Correct event flow for streaming vs synchronous

### ❌ What Tests Don't Assert

- **Exact strings**: "The answer is 4" (too brittle)
- **Model intelligence**: Whether the answer is "correct"
- **Specific wording**: Word choice, phrasing, formatting

### Handling Non-Determinism

Tests handle non-deterministic LLM behavior gracefully:

```kotlin
// ✅ GOOD: Accept multiple valid outcomes
if (toolCallEvents.isNotEmpty()) {
    // Model chose to call tool (expected)
    assertThat(toolCallEvents.first().toolCalls).isNotEmpty()
} else {
    // Model chose not to call tool (also valid)
    println("ℹ️  Model did not call tool")
}
```

## Common Test Scenarios

### 1. Simple Question (No Tools)

Tests that the strategy can handle basic Q&A:
- Completes successfully
- Returns final response
- Doesn't try to call non-existent tools
- Reports token usage

### 2. Streaming vs Synchronous

Tests both streaming modes:
- `PROGRESSIVE`: Emits `ContentChunk` events
- `FINAL_ONLY`: Emits `FinalResponse` only

### 3. Tool Calling

Tests tool calling behavior:
- Detects when tools should be called
- Properly formats tool call arguments
- Sets `shouldContinue = true` when tool called

### 4. Reasoning Tasks

Tests complex reasoning:
- Handles multi-step problems
- Produces substantive responses
- (Optionally) exposes reasoning traces

## Cost Management

Integration tests make real API calls. Here's how to manage costs:

### 1. Use Cheaper Models

```bash
# Use cheaper models for testing
export DEEPSEEK_TEST_MODEL="deepseek-chat"  # not deepseek-reasoner
```

### 2. Run Selective Tests

Don't run all tests every time:

```bash
# During development: Run specific tests
./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest.should complete simple question" \
  -Dspring.profiles.active=integration-test

# Before commit: Run fast tests only
./gradlew test --tests "StrategyMechanicsTest" \
  -Dspring.profiles.active=integration-test
```

### 3. Use Shorter Prompts

For mechanics testing, use simple prompts:
- "Say hello" instead of complex questions
- "Count to 3" instead of "Count to 100"
- Test structure, not content quality

### 4. Set Reasonable Limits

Configure conservative limits in `application-integration-test.yml`:
```yaml
loop:
  max-iterations: 3  # Lower for testing
  max-tokens: 1000   # Prevent runaway generation
```

## Analyzing Test Results

### Manual Review Process

1. **Run tests and save outputs**:
   ```bash
   ./gradlew test --tests "*IntegrationTest" -Dspring.profiles.active=integration-test
   ```

2. **Review test outputs**:
   ```bash
   ls -lt test-outputs/ | head -10  # View recent outputs
   cat test-outputs/deepseek_*.txt  # Review specific strategy
   ```

3. **Look for patterns**:
   - Did the model call the right tools?
   - Are responses substantive and coherent?
   - Are token counts reasonable?
   - Are there any errors or unexpected behaviors?

4. **Compare strategies**:
   ```bash
   # Run same test with different strategies
   TEST_STRATEGY=deepseek_single ./gradlew test --tests "..." -Dspring.profiles.active=integration-test
   TEST_STRATEGY=qwen_single_thinking ./gradlew test --tests "..." -Dspring.profiles.active=integration-test

   # Compare outputs
   diff test-outputs/deepseek_*.txt test-outputs/qwen_*.txt
   ```

### Metrics to Track

When analyzing test results, track:
- **Token usage**: Average tokens per iteration
- **Response quality**: Coherence, completeness, correctness (manual)
- **Tool calling**: Precision (called when needed), recall (didn't miss opportunities)
- **Latency**: Time to first token, total iteration time
- **Error rates**: Failed iterations, timeouts, API errors

## Troubleshooting

### Tests Fail with "API Key Not Set"

Set environment variables:
```bash
export DEEPSEEK_API_KEY="your-key-here"
export QWEN_API_KEY="your-key-here"
```

### Tests Timeout

Increase timeout in `application-integration-test.yml`:
```yaml
deepseek:
  api:
    timeout-seconds: 120  # Increase if needed
```

### Tests are Too Expensive

- Use cheaper models (`deepseek-chat` instead of `deepseek-reasoner`)
- Run selective tests instead of full suite
- Use shorter prompts for mechanics testing
- Lower `max-tokens` limit

### Output Directory Not Found

Create it manually:
```bash
mkdir -p test-outputs
```

Or configure a different location:
```bash
TEST_OUTPUT_DIR=/tmp/rag-test-outputs ./gradlew test ...
```

## Best Practices

1. **✅ Run tests before modifying a strategy**
   - Establish baseline behavior
   - Save outputs for comparison

2. **✅ Run tests after modifying a strategy**
   - Verify changes work as expected
   - Compare with baseline outputs

3. **✅ Save notable test outputs**
   - Copy interesting outputs to `test-outputs/examples/`
   - Document surprising behaviors
   - Share with team for discussion

4. **✅ Review outputs manually**
   - Don't just rely on passing tests
   - Check if responses make sense
   - Look for quality regressions

5. **❌ Don't run full suite on every change**
   - Too expensive and slow
   - Run targeted tests during development
   - Save full suite for weekly reviews

## Example Workflow

### During Strategy Development

```bash
# 1. Establish baseline
TEST_STRATEGY=deepseek_single ./gradlew test \
  --tests "DeepSeekSingleStrategyIntegrationTest.should complete simple question" \
  -Dspring.profiles.active=integration-test

# 2. Save baseline output
cp test-outputs/deepseek_simple_math_*.txt test-outputs/baseline_simple_math.txt

# 3. Modify strategy implementation
# (edit DeepSeekSingleStrategy.kt)

# 4. Run same test again
TEST_STRATEGY=deepseek_single ./gradlew test \
  --tests "DeepSeekSingleStrategyIntegrationTest.should complete simple question" \
  -Dspring.profiles.active=integration-test

# 5. Compare outputs
diff test-outputs/baseline_simple_math.txt test-outputs/deepseek_simple_math_*.txt

# 6. Review changes
cat test-outputs/deepseek_simple_math_*.txt
```

### Before Committing Changes

```bash
# Run mechanics tests (fast, cheap)
./gradlew test --tests "StrategyMechanicsTest" \
  -Dspring.profiles.active=integration-test

# Run integration tests for modified strategy
./gradlew test --tests "DeepSeekSingleStrategyIntegrationTest" \
  -Dspring.profiles.active=integration-test

# Manual review of outputs
ls -lt test-outputs/ | head -5
cat test-outputs/deepseek_*.txt

# If all looks good, commit!
```

## Summary

**Quick Start**:
```bash
export DEEPSEEK_API_KEY="sk-..."
export QWEN_API_KEY="sk-..."
./gradlew test --tests "*IntegrationTest" -Dspring.profiles.active=integration-test
```

**Key Points**:
- ✅ Tests make REAL API calls
- ✅ Outputs saved to `test-outputs/` for manual review
- ✅ Assertions are loose (structure, not content)
- ✅ Tests verify strategy mechanics, not model intelligence
- ✅ Use cheaper models to reduce costs

**Best Practices**:
1. Run targeted tests during development
2. Review test outputs manually
3. Compare before/after when modifying strategies
4. Save notable outputs for documentation
5. Use cheaper models for routine testing
