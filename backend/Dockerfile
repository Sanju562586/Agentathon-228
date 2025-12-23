# FINAL STRATEGY: Pre-compiled Binary
# We build the binary locally in deploy_gcp.ps1 and just copy it here.
FROM alpine:latest

WORKDIR /root/

# Copy the locally compiled linux binary
COPY app_linux ./main
RUN chmod +x ./main

EXPOSE 8080

CMD ["./main"]
