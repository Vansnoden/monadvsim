#!/bin/bash
# =============================================================================
# merge_netcdf.sh - Merge multiple NetCDF files with different time periods
# =============================================================================

set -e

# -----------------------------------------------------------------------------
# Color codes
# -----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info() { echo -e "${GREEN}[INFO]${NC} $1"; }
warning() { echo -e "${YELLOW}[WARNING]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# -----------------------------------------------------------------------------
# Default values
# -----------------------------------------------------------------------------
INPUT_DIR="."
OUTPUT_FILE="merged_climate.nc"
FILE_PATTERN="*.nc"
VARIABLES=""
TIME_DIM="time"
VERBOSE=false

# -----------------------------------------------------------------------------
# Parse arguments
# -----------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case $1 in
        -i|--input-dir)
            INPUT_DIR="$2"
            shift 2
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -p|--pattern)
            FILE_PATTERN="$2"
            shift 2
            ;;
        -v|--variables)
            VARIABLES="$2"
            shift 2
            ;;
        -t|--time-dim)
            TIME_DIM="$2"
            shift 2
            ;;
        -V|--verbose)
            VERBOSE=true
            shift
            ;;
        -h|--help)
            cat << EOF
Usage: $0 [OPTIONS]

Options:
  -i, --input-dir DIR     Input directory (default: .)
  -o, --output FILE       Output file (default: merged_climate.nc)
  -p, --pattern PATTERN   File pattern (default: *.nc)
  -v, --variables LIST    Keep only these variables (comma-separated)
  -t, --time-dim NAME     Time dimension name (default: time)
  -V, --verbose           Verbose output
  -h, --help              Show this help

Examples:
  $0 -i ./data -o merged.nc
  $0 -i ./data -p "climate_*.nc" -v "t2m,tp"
EOF
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            ;;
    esac
done

# -----------------------------------------------------------------------------
# Check prerequisites
# -----------------------------------------------------------------------------
if ! command -v cdo &> /dev/null; then
    error "CDO is not installed. Install with: sudo apt-get install cdo"
fi

if ! command -v ncks &> /dev/null; then
    warning "NCO tools not found. Some features may not work. Install with: sudo apt-get install nco"
fi

# -----------------------------------------------------------------------------
# Validate input
# -----------------------------------------------------------------------------
if [ ! -d "$INPUT_DIR" ]; then
    error "Input directory does not exist: $INPUT_DIR"
fi

# -----------------------------------------------------------------------------
# Find NetCDF files
# -----------------------------------------------------------------------------
cd "$INPUT_DIR"
FILES=($(ls -1 $FILE_PATTERN 2>/dev/null | sort))
cd - > /dev/null

if [ ${#FILES[@]} -eq 0 ]; then
    error "No NetCDF files found matching pattern '$FILE_PATTERN' in $INPUT_DIR"
fi

info "Found ${#FILES[@]} NetCDF files to merge"

# Show file details
for file in "${FILES[@]}"; do
    FILE_SIZE=$(du -h "$INPUT_DIR/$file" 2>/dev/null | cut -f1)
    TIME_STEPS=$(cdo -s ntime "$INPUT_DIR/$file" 2>/dev/null || echo "N/A")
    echo "  - $file (size: $FILE_SIZE, time steps: $TIME_STEPS)"
done
echo ""

# -----------------------------------------------------------------------------
# Create temporary directory
# -----------------------------------------------------------------------------
TEMPORARY_DIR=$(mktemp -d -t netcdf_merge_XXXXXX)
trap "rm -rf $TEMPORARY_DIR" EXIT

# -----------------------------------------------------------------------------
# METHOD 1: Use CDO with individual file arguments (NOT a file list)
# -----------------------------------------------------------------------------
info "Merging ${#FILES[@]} files using CDO mergetime..."

# Build the command with individual file paths
CMD="cdo -O mergetime"
for file in "${FILES[@]}"; do
    CMD="$CMD $INPUT_DIR/$file"
done
CMD="$CMD $TEMPORARY_DIR/merged_temp1.nc"

if [ "$VERBOSE" = true ]; then
    info "Running: $CMD"
fi

# Execute the command
if eval $CMD 2>&1; then
    info "Merge with mergetime succeeded"
else
    warning "Mergetime failed, trying concatenation..."
    
    # Try concatenation
    CMD="cdo -O cat"
    for file in "${FILES[@]}"; do
        CMD="$CMD $INPUT_DIR/$file"
    done
    CMD="$CMD $TEMPORARY_DIR/merged_temp1.nc"
    
    if eval $CMD 2>&1; then
        info "Concatenation succeeded"
    else
        error "Both mergetime and concatenation failed"
    fi
fi

# -----------------------------------------------------------------------------
# METHOD 2: Alternative - Use NCO if available (more robust)
# -----------------------------------------------------------------------------
if command -v ncks &> /dev/null && [ ! -f "$TEMPORARY_DIR/merged_temp1.nc" ]; then
    info "Trying NCO method..."
    
    # Use ncecat to concatenate
    NCO_CMD="ncecat -O"
    for file in "${FILES[@]}"; do
        NCO_CMD="$NCO_CMD $INPUT_DIR/$file"
    done
    NCO_CMD="$NCO_CMD $TEMPORARY_DIR/merged_temp2.nc"
    
    if eval $NCO_CMD 2>&1; then
        info "NCO concatenation succeeded"
        mv "$TEMPORARY_DIR/merged_temp2.nc" "$TEMPORARY_DIR/merged_temp1.nc"
    else
        error "All merge methods failed"
    fi
fi

# -----------------------------------------------------------------------------
# Check if merge produced a valid file
# -----------------------------------------------------------------------------
if [ ! -f "$TEMPORARY_DIR/merged_temp1.nc" ] || [ ! -s "$TEMPORARY_DIR/merged_temp1.nc" ]; then
    error "Merge failed - output file is empty or missing"
fi

info "Initial merge completed successfully"

# -----------------------------------------------------------------------------
# Filter variables if requested
# -----------------------------------------------------------------------------
TEMP_FILE="$TEMPORARY_DIR/merged_temp1.nc"

if [ -n "$VARIABLES" ]; then
    info "Filtering variables: $VARIABLES"
    
    if command -v ncks &> /dev/null; then
        # Use NCO for variable filtering (more reliable)
        ncks -O -v $VARIABLES "$TEMP_FILE" "$TEMPORARY_DIR/merged_filtered.nc" 2>/dev/null || {
            warning "NCO filtering failed, trying CDO..."
            cdo -O selvar,$VARIABLES "$TEMP_FILE" "$TEMPORARY_DIR/merged_filtered.nc" 2>/dev/null || {
                warning "Variable filtering failed - using all variables"
                cp "$TEMP_FILE" "$TEMPORARY_DIR/merged_filtered.nc"
            }
        }
    else
        # Use CDO
        cdo -O selvar,$VARIABLES "$TEMP_FILE" "$TEMPORARY_DIR/merged_filtered.nc" 2>/dev/null || {
            warning "Variable filtering failed - using all variables"
            cp "$TEMP_FILE" "$TEMPORARY_DIR/merged_filtered.nc"
        }
    fi
    TEMP_FILE="$TEMPORARY_DIR/merged_filtered.nc"
fi

# -----------------------------------------------------------------------------
# Copy to final output
# -----------------------------------------------------------------------------
cp "$TEMP_FILE" "$OUTPUT_FILE"

# -----------------------------------------------------------------------------
# Verify output
# -----------------------------------------------------------------------------
if [ -f "$OUTPUT_FILE" ] && [ -s "$OUTPUT_FILE" ]; then
    info "✅ Merge completed successfully!"
    info "Output file: $OUTPUT_FILE"
    
    # Show file info
    echo ""
    info "Output file information:"
    cdo -s sinfo "$OUTPUT_FILE" 2>/dev/null | head -15
    
    # Show time range
    echo ""
    info "Time range:"
    cdo -s outputtime "$OUTPUT_FILE" 2>/dev/null | head -1
    cdo -s outputtime "$OUTPUT_FILE" 2>/dev/null | tail -1
    
    echo ""
    FILE_SIZE=$(du -h "$OUTPUT_FILE" | cut -f1)
    info "File size: $FILE_SIZE"
    
    # Count time steps
    TIME_STEPS=$(cdo -s ntime "$OUTPUT_FILE" 2>/dev/null || echo "N/A")
    info "Total time steps: $TIME_STEPS"
else
    error "Output file not created successfully"
fi
