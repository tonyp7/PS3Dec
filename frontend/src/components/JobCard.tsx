import type { Job } from '@/api/types'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Progress } from '@/components/ui/progress'
import { formatBytes, formatDuration, formatRate } from '@/lib/format'

const LOG_TAIL = 8

function LogTail({ lines }: { lines: string[] }) {
  if (lines.length === 0) return null
  return (
    <pre
      aria-label="Tool output"
      className="max-h-40 overflow-auto rounded-md bg-muted p-2 font-mono text-xs whitespace-pre-wrap text-muted-foreground"
    >
      {lines.slice(-LOG_TAIL).join('\n')}
    </pre>
  )
}

interface Props {
  job: Job | null | undefined
  onCancel: () => void
  cancelling?: boolean
}

export function JobCard({ job, onCancel, cancelling }: Props) {
  if (!job) {
    return (
      <Card>
        <CardHeader>
          <CardTitle>Job</CardTitle>
          <CardDescription>No job has run yet. Pick an image and press Start.</CardDescription>
        </CardHeader>
      </Card>
    )
  }

  const verb = job.mode === 'decrypt' ? 'Decrypting' : 'Encrypting'
  const pastVerb = job.mode === 'decrypt' ? 'Decrypted' : 'Encrypted'

  if (job.state === 'running') {
    const pct = Math.floor(job.fraction * 100)
    const finishing = job.fraction >= 1
    return (
      <Card>
        <CardHeader>
          <CardTitle>
            {verb} {job.iso}
          </CardTitle>
          <CardDescription>
            {finishing
              ? 'Finishing…'
              : `${pct}% · ${formatBytes(job.bytes_done)} of ${formatBytes(job.bytes_total)}`}
          </CardDescription>
          <CardAction>
            <Button variant="destructive" size="sm" onClick={onCancel} disabled={cancelling}>
              {cancelling ? 'Cancelling…' : 'Cancel'}
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Progress value={job.fraction * 100} aria-label="Progress" />
          <dl className="grid grid-cols-3 gap-2 text-sm">
            <div>
              <dt className="text-xs text-muted-foreground">Elapsed</dt>
              <dd className="tabular-nums">{formatDuration(job.elapsed_s)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Speed</dt>
              <dd className="tabular-nums">{job.throughput_bps ? formatRate(job.throughput_bps) : '—'}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Remaining</dt>
              <dd className="tabular-nums">{!finishing && job.eta_s != null ? formatDuration(job.eta_s) : '—'}</dd>
            </div>
          </dl>
          <LogTail lines={job.log} />
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Job</CardTitle>
        <CardDescription>
          {job.iso} · {job.mode}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {job.state === 'succeeded' && (
          <Alert>
            <AlertTitle>{pastVerb} successfully</AlertTitle>
            <AlertDescription>
              <span className="font-medium">{job.output}</span> ({formatBytes(job.bytes_total)}) is in the output
              folder. Took {formatDuration(job.elapsed_s)}.
            </AlertDescription>
          </Alert>
        )}
        {job.state === 'failed' && (
          <Alert variant="destructive">
            <AlertTitle>Failed</AlertTitle>
            <AlertDescription>{job.error ?? 'The conversion failed.'} No output was kept.</AlertDescription>
          </Alert>
        )}
        {job.state === 'cancelled' && (
          <Alert>
            <AlertTitle>Cancelled</AlertTitle>
            <AlertDescription>The conversion was cancelled and the partial output was removed.</AlertDescription>
          </Alert>
        )}
        {job.state !== 'succeeded' && <LogTail lines={job.log} />}
      </CardContent>
    </Card>
  )
}
