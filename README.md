# School Manager

## Running the App

### Backend

```bash
cd backend

# Start the server (port 8080)
./gradlew bootRun

# Watch for changes and restart automatically
./gradlew bootRun --continuous

# Compile only, watch for changes
./gradlew compileJava --continuous
```

### Frontend

```bash
cd frontend

# Install dependencies (first time only)
pnpm install

# Start the dev server (port 3000)
pnpm dev
```

### Full Stack

```bash
# Terminal 1
cd backend && ./gradlew bootRun

# Terminal 2
cd frontend && pnpm dev
```

Visit http://localhost:3000
