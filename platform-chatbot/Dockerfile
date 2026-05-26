FROM python:3.12-slim

WORKDIR /app

# Install dependencies from pyproject.toml before copying source
# (layer is cached as long as pyproject.toml doesn't change)
COPY pyproject.toml .
RUN pip install --no-cache-dir \
    "langgraph>=0.2" \
    "langchain-anthropic>=0.2" \
    "langchain-core>=0.3" \
    "fastapi>=0.115" \
    "uvicorn[standard]>=0.30" \
    "httpx>=0.27" \
    "pydantic>=2.7" \
    "python-dotenv>=1.0"

# Copy source code
COPY src/ ./src/

EXPOSE 8000

CMD ["uvicorn", "src.main:app", "--host", "0.0.0.0", "--port", "8000"]
