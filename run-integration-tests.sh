#!/bin/bash

# Integration Test Runner for RAG Orchestrator Strategy Tests
#
# This script simplifies running integration tests with real API calls.
# It provides convenient shortcuts for common test scenarios.
#
# Usage:
#   ./run-integration-tests.sh [command] [options]
#
# Commands:
#   all              - Run all integration tests
#   deepseek         - Run DeepSeek strategy tests only
#   qwen-thinking    - Run Qwen thinking strategy tests only
#   qwen-instruct    - Run Qwen instruct strategy tests only
#   mechanics        - Run strategy mechanics tests (fast, cheap)
#   help             - Show this help message
#
# Environment Variables:
#   DEEPSEEK_API_KEY - Required for DeepSeek tests
#   QWEN_API_KEY     - Required for Qwen tests

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Print colored message
print_info() {
    echo -e "${BLUE}ℹ ${NC}$1"
}

print_success() {
    echo -e "${GREEN}✓ ${NC}$1"
}

print_warning() {
    echo -e "${YELLOW}⚠ ${NC}$1"
}

print_error() {
    echo -e "${RED}✗ ${NC}$1"
}

print_header() {
    echo ""
    echo -e "${BLUE}${"="*60}${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}${"="*60}${NC}"
    echo ""
}

# Check if API keys are set
check_api_keys() {
    local missing_keys=false

    if [ -z "$DEEPSEEK_API_KEY" ]; then
        print_warning "DEEPSEEK_API_KEY not set"
        missing_keys=true
    fi

    if [ -z "$QWEN_API_KEY" ]; then
        print_warning "QWEN_API_KEY not set"
        missing_keys=true
    fi

    if [ "$missing_keys" = true ]; then
        echo ""
        print_info "Set API keys before running tests:"
        echo "  export DEEPSEEK_API_KEY=\"sk-...\""
        echo "  export QWEN_API_KEY=\"sk-...\""
        echo ""
        read -p "Continue anyway? (y/N) " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
}

# Create output directory
setup_output_dir() {
    mkdir -p test-outputs
    print_success "Output directory: test-outputs/"
}

# Run gradle test command
run_tests() {
    local test_pattern="$1"
    local strategy="${2:-}"

    print_header "Running Tests"
    print_info "Test pattern: $test_pattern"
    if [ -n "$strategy" ]; then
        print_info "Strategy: $strategy"
    fi

    # Build gradle command
    local gradle_cmd="./gradlew test --tests \"$test_pattern\" -Dspring.profiles.active=integration-test"

    # Add strategy if specified
    if [ -n "$strategy" ]; then
        gradle_cmd="TEST_STRATEGY=$strategy $gradle_cmd"
    fi

    # Run tests
    echo ""
    print_info "Command: $gradle_cmd"
    echo ""
    eval "$gradle_cmd"

    echo ""
    print_success "Tests completed!"
    print_info "Review outputs in: test-outputs/"
    echo ""
}

# Show help
show_help() {
    cat << EOF
Integration Test Runner for RAG Orchestrator

Usage:
  ./run-integration-tests.sh [command]

Commands:
  all              - Run all integration tests
  deepseek         - Run DeepSeek strategy tests only
  qwen-thinking    - Run Qwen thinking strategy tests only
  qwen-instruct    - Run Qwen instruct strategy tests only
  mechanics        - Run strategy mechanics tests (fast, cheap)
  help             - Show this help message

Environment Variables:
  DEEPSEEK_API_KEY - Required for DeepSeek tests
  QWEN_API_KEY     - Required for Qwen tests

Optional Configuration:
  DEEPSEEK_TEST_MODEL          - Override DeepSeek model (default: deepseek-chat)
  QWEN_TEST_MODEL_INSTRUCT     - Override Qwen instruct model (default: qwen-plus)
  QWEN_TEST_MODEL_THINKING     - Override Qwen thinking model (default: qwen-plus)
  TEST_OUTPUT_DIR              - Override output directory (default: test-outputs)

Examples:
  # Run all tests
  ./run-integration-tests.sh all

  # Run DeepSeek tests only
  ./run-integration-tests.sh deepseek

  # Use cheaper model for testing
  DEEPSEEK_TEST_MODEL=deepseek-chat ./run-integration-tests.sh deepseek

  # Run mechanics tests (fast and cheap)
  ./run-integration-tests.sh mechanics

For more information, see: TEST_RUNNER_README.md
EOF
}

# Main script logic
main() {
    local command="${1:-help}"

    # Always check API keys (except for help)
    if [ "$command" != "help" ]; then
        check_api_keys
        setup_output_dir
    fi

    case "$command" in
        all)
            run_tests "*IntegrationTest"
            ;;
        deepseek)
            run_tests "DeepSeekSingleStrategyIntegrationTest" "deepseek_single"
            ;;
        qwen-thinking)
            run_tests "QwenSingleThinkingStrategyIntegrationTest" "qwen_single_thinking"
            ;;
        qwen-instruct)
            run_tests "QwenSingleInstructStrategyIntegrationTest" "qwen_single_instruct"
            ;;
        mechanics)
            print_info "Running fast, cheap mechanics tests"
            run_tests "StrategyMechanicsTest"
            ;;
        help|--help|-h)
            show_help
            ;;
        *)
            print_error "Unknown command: $command"
            echo ""
            show_help
            exit 1
            ;;
    esac
}

# Run main function
main "$@"
