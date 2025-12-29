#!/bin/bash

# Script to update GitHub repository secrets for Maven Central deployment
# Requires: gh CLI tool (https://cli.github.com/)

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}GitHub Secrets Updater for Maven Central Deployment${NC}"
echo "========================================================"
echo ""

# Check if gh is installed
if ! command -v gh &> /dev/null; then
    echo -e "${RED}Error: GitHub CLI (gh) is not installed.${NC}"
    echo "Install it from: https://cli.github.com/"
    exit 1
fi

# Check if authenticated with gh
if ! gh auth status &> /dev/null; then
    echo -e "${RED}Error: Not authenticated with GitHub CLI.${NC}"
    echo "Run: gh auth login"
    exit 1
fi

# Get the repository (current directory)
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || echo "")
if [ -z "$REPO" ]; then
    echo -e "${RED}Error: Not in a GitHub repository directory.${NC}"
    exit 1
fi

echo -e "Repository: ${GREEN}$REPO${NC}"
echo ""

# Parse Maven settings.xml
SETTINGS_FILE="${HOME}/.m2/settings.xml"

if [ ! -f "$SETTINGS_FILE" ]; then
    echo -e "${YELLOW}Warning: Maven settings.xml not found at $SETTINGS_FILE${NC}"
    echo "Will prompt for Maven credentials manually."
    MAVEN_USERNAME=""
    MAVEN_PASSWORD=""
else
    echo "Parsing Maven settings from: $SETTINGS_FILE"

    # Extract OSSRH credentials using xmllint or grep/sed
    if command -v xmllint &> /dev/null; then
        MAVEN_USERNAME=$(xmllint --xpath "string(//servers/server[id='ossrh']/username)" "$SETTINGS_FILE" 2>/dev/null || echo "")
        MAVEN_PASSWORD=$(xmllint --xpath "string(//servers/server[id='ossrh']/password)" "$SETTINGS_FILE" 2>/dev/null || echo "")
        GPG_KEYNAME=$(xmllint --xpath "string(//profiles/profile/properties/gpg.keyname)" "$SETTINGS_FILE" 2>/dev/null || echo "")
        GPG_PASSPHRASE=$(xmllint --xpath "string(//profiles/profile/properties/gpg.passphrase)" "$SETTINGS_FILE" 2>/dev/null || echo "")
    else
        # Fallback to grep/sed
        MAVEN_USERNAME=$(grep -A 5 "<id>ossrh</id>" "$SETTINGS_FILE" | grep "<username>" | sed 's/.*<username>\(.*\)<\/username>.*/\1/' | tr -d ' ' || echo "")
        MAVEN_PASSWORD=$(grep -A 5 "<id>ossrh</id>" "$SETTINGS_FILE" | grep "<password>" | sed 's/.*<password>\(.*\)<\/password>.*/\1/' | tr -d ' ' || echo "")
        GPG_KEYNAME=$(grep "<gpg.keyname>" "$SETTINGS_FILE" | sed 's/.*<gpg.keyname>\(.*\)<\/gpg.keyname>.*/\1/' | tr -d ' ' || echo "")
        GPG_PASSPHRASE=$(grep "<gpg.passphrase>" "$SETTINGS_FILE" | sed 's/.*<gpg.passphrase>\(.*\)<\/gpg.passphrase>.*/\1/' | tr -d ' ' || echo "")
    fi
fi

# Prompt for missing Maven credentials
if [ -z "$MAVEN_USERNAME" ]; then
    echo -e "${YELLOW}Maven username not found in settings.xml${NC}"
    read -p "Enter Maven Central (OSSRH) username: " MAVEN_USERNAME
fi

if [ -z "$MAVEN_PASSWORD" ]; then
    echo -e "${YELLOW}Maven password not found in settings.xml${NC}"
    read -sp "Enter Maven Central (OSSRH) password/token: " MAVEN_PASSWORD
    echo ""
fi

# GPG Key handling
if [ -z "$GPG_KEYNAME" ]; then
    echo ""
    echo -e "${YELLOW}GPG key name not found in settings.xml${NC}"
    echo "Available GPG keys:"
    gpg --list-secret-keys --keyid-format LONG 2>/dev/null || echo "No GPG keys found"
    echo ""
    read -p "Enter GPG key ID (e.g., ABCD1234EFGH5678): " GPG_KEYNAME
fi

if [ -z "$GPG_PASSPHRASE" ]; then
    echo -e "${YELLOW}GPG passphrase not found in settings.xml${NC}"
    read -sp "Enter GPG key passphrase: " GPG_PASSPHRASE
    echo ""
fi

# Export GPG private key
echo ""
echo "Exporting GPG private key..."
GPG_PRIVATE_KEY=$(gpg --batch --pinentry-mode loopback --passphrase "$GPG_PASSPHRASE" --export-secret-keys --armor "$GPG_KEYNAME" 2>/dev/null)

if [ -z "$GPG_PRIVATE_KEY" ]; then
    echo -e "${RED}Error: Could not export GPG private key for: $GPG_KEYNAME${NC}"
    echo "Make sure the key exists, you have access to it, and the passphrase is correct."
    exit 1
fi

# Confirm before updating
echo ""
echo -e "${YELLOW}Ready to update the following secrets in $REPO:${NC}"
echo "  - MAVEN_USERNAME: $MAVEN_USERNAME"
echo "  - MAVEN_PASSWORD: [hidden]"
echo "  - GPG_KEYNAME: $GPG_KEYNAME"
echo "  - GPG_PASSPHRASE: [hidden]"
echo "  - GPG_PRIVATE_KEY: [exported]"
echo ""
read -p "Continue? (y/N): " -n 1 -r
echo ""

if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 0
fi

# Update secrets
echo ""
echo "Updating GitHub secrets..."

echo -n "Setting MAVEN_USERNAME... "
echo -n "$MAVEN_USERNAME" | gh secret set MAVEN_USERNAME
echo -e "${GREEN}✓${NC}"

echo -n "Setting MAVEN_PASSWORD... "
echo -n "$MAVEN_PASSWORD" | gh secret set MAVEN_PASSWORD
echo -e "${GREEN}✓${NC}"

echo -n "Setting GPG_KEYNAME... "
echo -n "$GPG_KEYNAME" | gh secret set GPG_KEYNAME
echo -e "${GREEN}✓${NC}"

echo -n "Setting GPG_PASSPHRASE... "
echo -n "$GPG_PASSPHRASE" | gh secret set GPG_PASSPHRASE
echo -e "${GREEN}✓${NC}"

echo -n "Setting GPG_PRIVATE_KEY... "
echo -n "$GPG_PRIVATE_KEY" | gh secret set GPG_PRIVATE_KEY
echo -e "${GREEN}✓${NC}"

echo ""
echo -e "${GREEN}✓ All secrets updated successfully!${NC}"
echo ""
echo "You can verify the secrets at:"
echo "  https://github.com/$REPO/settings/secrets/actions"