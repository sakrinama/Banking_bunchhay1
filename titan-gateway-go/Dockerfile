FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 GOOS=linux go build -o titan-gateway .

FROM gcr.io/distroless/static:nonroot
WORKDIR /app
COPY --from=builder /app/titan-gateway .
COPY config.yaml .
EXPOSE 8088
CMD ["/app/titan-gateway"]
