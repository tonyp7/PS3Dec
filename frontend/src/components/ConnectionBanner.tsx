import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'

export function ConnectionBanner({ error }: { error: Error | null }) {
  if (!error) return null
  return (
    <Alert variant="destructive">
      <AlertTitle>Can’t reach the PS3Dec backend</AlertTitle>
      <AlertDescription>Retrying automatically. Your selection is kept.</AlertDescription>
    </Alert>
  )
}
