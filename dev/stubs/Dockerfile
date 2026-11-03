FROM python:3.13-alpine
WORKDIR /stub
COPY server.py .
RUN mkdir /data && chown -R 10001:10001 /data /stub
USER 10001:10001
EXPOSE 8080
CMD ["python", "server.py"]
